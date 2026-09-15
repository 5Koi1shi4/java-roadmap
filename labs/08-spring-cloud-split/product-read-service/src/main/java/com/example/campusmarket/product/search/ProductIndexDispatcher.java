package com.example.campusmarket.product.search;

import com.example.campusmarket.product.infrastructure.ProductIndexOutboxClaimer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 从读侧索引待办投递商品投影；ES 失败时保留待办以便重试。 */
@Component
public final class ProductIndexDispatcher {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

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
                // 失败和旧 token 都不能伪造 PUBLISHED；当前 token 仍有效时释放为可重试。
                claimer.markRetry(claim, RETRY_DELAY);
            }
        }
        return published;
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

    private record Projection(String listingId, long aggregateVersion, String title, String description,
                              String category, long unitPriceFen, int availableQuantity, String status) {
        private ProductSearchPort.ProductDocument document() {
            return new ProductSearchPort.ProductDocument(listingId, title, description, category,
                    unitPriceFen, availableQuantity, status, aggregateVersion);
        }
    }
}
