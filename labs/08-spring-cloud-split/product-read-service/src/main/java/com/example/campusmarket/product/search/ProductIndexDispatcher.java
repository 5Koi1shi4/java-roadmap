package com.example.campusmarket.product.search;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.example.campusmarket.product.infrastructure.ProductIndexOutboxClaimer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从读侧索引待办投递商品投影；ES 失败时保留待办以便重试。 */
@Component
public final class ProductIndexDispatcher {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "HTTP(?:/[0-9.]+)?\\s+(\\d{3})|\\bstatus(?:[ _-]?code)?[^0-9]*(\\d{3})\\b",
            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final ProductIndexOutboxClaimer claimer;
    private final ProductSearchPort search;

    public ProductIndexDispatcher(JdbcTemplate jdbc, ProductIndexOutboxClaimer claimer,
                                  ProductSearchPort search) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.claimer = Objects.requireNonNull(claimer, "索引待办领取器不能为空");
        this.search = Objects.requireNonNull(search, "搜索端口不能为空");
    }

    /** 投递一批待办，返回本次以当前 token 成功标记 PUBLISHED 的数量。 */
    public int dispatchOnce(String ownerId, int limit, Duration lease) {
        List<ProductIndexOutboxClaimer.Claim> claims = claimer.claimBatch(ownerId, limit, lease);
        int published = 0;
        for (ProductIndexOutboxClaimer.Claim claim : claims) {
            try {
                Projection projection = projection(claim.listingId());
                if (projection != null) {
                    validateProjection(projection);
                }
                if (projection != null && projection.aggregateVersion() < claim.aggregateVersion()) {
                    claimer.markRetry(claim, RETRY_DELAY);
                    continue;
                }
                if (!claimer.isCurrentOpen(claim)) {
                    claimer.markRetry(claim, RETRY_DELAY);
                    continue;
                }
                if (projection != null && isVisible(projection)) {
                    search.index(projection.document());
                } else {
                    long version = projection == null
                            ? claim.aggregateVersion() : projection.aggregateVersion();
                    search.tombstone(claim.listingId(), version);
                }
                search.refresh();
                if (!claimer.isCurrentOpen(claim)) {
                    claimer.markRetry(claim, RETRY_DELAY);
                    continue;
                }
                if (claimer.markPublished(claim)) {
                    published++;
                }
            } catch (RuntimeException failure) {
                if (isPermanentFailure(failure)) {
                    // 只有当前 owner/token 且租约仍有效时才能进入永久失败终态。
                    markPermanentFailure(claim, failure);
                } else {
                    // 连接中断、503、408、429 等短暂故障始终保留 NEW 重试。
                    claimer.markRetry(claim, RETRY_DELAY);
                }
            }
        }
        return published;
    }

    private void markPermanentFailure(ProductIndexOutboxClaimer.Claim claim, RuntimeException failure) {
        String detail = failureDetail(failure);
        jdbc.update("""
            UPDATE product_index_outbox
            SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                failure_class='PERMANENT',last_error=?
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, detail, claim.id(), claim.ownerId(), claim.claimToken());
    }

    private Projection projection(String listingId) {
        List<Projection> rows = jdbc.query("""
            SELECT listing_id,aggregate_version,title,description,category,
                   unit_price_fen,available_quantity,status
            FROM product_projection
            WHERE listing_id=?
            """, (result, row) -> new Projection(
                result.getString("listing_id"),
                result.getLong("aggregate_version"),
                result.getString("title"),
                result.getString("description"),
                result.getString("category"),
                result.getLong("unit_price_fen"),
                result.getInt("available_quantity"),
                result.getString("status")), listingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean isVisible(Projection projection) {
        return "ON_SALE".equals(projection.status()) && projection.availableQuantity() > 0;
    }

    private static void validateProjection(Projection projection) {
        if (projection.listingId() == null || projection.listingId().isBlank()
                || projection.status() == null || !ProductSearchPort.STATUS_VALUES.contains(projection.status())
                || "TOMBSTONE".equals(projection.status())) {
            throw new IllegalArgumentException("商品投影状态无效");
        }
        // Reuse the document boundary so malformed text, price, quantity or version is permanent data.
        new ProductSearchPort.ProductDocument(projection.listingId(), projection.title(), projection.description(),
                projection.category(), projection.unitPriceFen(), projection.availableQuantity(),
                projection.status(), projection.aggregateVersion());
    }

    private static boolean isPermanentFailure(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllegalArgumentException) {
                return true;
            }
            Integer status = statusCode(current);
            if (status != null && status >= 400 && status < 500 && status != 408 && status != 429) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Integer statusCode(Throwable failure) {
        if (failure instanceof ElasticsearchException elasticsearchException) {
            return elasticsearchException.status();
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_STATUS.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String failureDetail(RuntimeException failure) {
        Integer status = null;
        String message = null;
        Throwable current = failure;
        while (current != null) {
            Integer currentStatus = statusCode(current);
            if (status == null && currentStatus != null) {
                status = currentStatus;
            }
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        if (status != null && !message.contains(String.valueOf(status))) {
            message = "ES HTTP " + status + ": " + message;
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private record Projection(String listingId, long aggregateVersion, String title, String description,
                              String category, long unitPriceFen, int availableQuantity, String status) {
        private ProductSearchPort.ProductDocument document() {
            return new ProductSearchPort.ProductDocument(listingId, title, description, category,
                    unitPriceFen, availableQuantity, status, aggregateVersion);
        }
    }
}
