package com.example.campusmarket.review;

import com.example.campusmarket.observability.AuditRecorder;
import com.example.campusmarket.observability.SafeAuditEvent;
import com.example.campusmarket.observability.CampusMetrics;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 已结算订单评价用例；订单参与者和唯一约束共同构成授权及幂等边界。 */
@Service
@Profile("!test")
public final class ReviewService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuditRecorder audits;
    private final CampusMetrics metrics;

    public ReviewService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                         AuditRecorder audits, CampusMetrics metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "评价数据库不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.audits = Objects.requireNonNull(audits, "审计记录器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标不能为空");
    }

    public ReviewService(JdbcTemplate jdbc, TransactionTemplate transactions,
                         AuditRecorder audits, CampusMetrics metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "评价数据库不能为空");
        this.transactions = Objects.requireNonNull(transactions, "事务模板不能为空");
        this.audits = Objects.requireNonNull(audits, "审计记录器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标不能为空");
    }

    public Result create(UUID orderId, UUID reviewerId, int rating, String reviewText) {
        validate(orderId, reviewerId, rating, reviewText);
        try {
            return transactions.execute(status -> createInTransaction(orderId, reviewerId, rating, reviewText));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException();
        }
    }

    public Result createReview(UUID orderId, UUID reviewerId, int rating, String reviewText) {
        return create(orderId, reviewerId, rating, reviewText);
    }

    public Result submit(UUID orderId, UUID reviewerId, int rating, String reviewText) {
        return create(orderId, reviewerId, rating, reviewText);
    }

    private Result createInTransaction(UUID orderId, UUID reviewerId, int rating, String reviewText) {
        OrderFacts order = jdbc.query("SELECT buyer_id,seller_id,status FROM trade_order WHERE id=? FOR UPDATE",
            rs -> rs.next() ? new OrderFacts(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3)) : null,
            orderId.toString());
        if (order == null || (!reviewerId.equals(order.buyerId()) && !reviewerId.equals(order.sellerId()))) {
            throw new NotFoundException();
        }
        if (!"SETTLED".equals(order.status())) {
            throw new UnprocessableException();
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM trade_review WHERE order_id=? AND reviewer_id=?",
            Integer.class, orderId.toString(), reviewerId.toString());
        if (existing != null && existing > 0) throw new ConflictException();

        UUID reviewId = UUID.randomUUID();
        UUID revieweeId = reviewerId.equals(order.buyerId()) ? order.sellerId() : order.buyerId();
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
        jdbc.update("INSERT INTO trade_review(id,order_id,reviewer_id,reviewee_id,rating,review_text,created_at) VALUES (?,?,?,?,?,?,?)",
            reviewId.toString(), orderId.toString(), reviewerId.toString(), revieweeId.toString(), rating, reviewText, Timestamp.from(now));
        audits.record(SafeAuditEvent.success(reviewerId, "REVIEW_CREATED", "TRADE_ORDER", orderId,
            Map.of("rating", rating)));
        metrics.recordReview("SUCCESS");
        return new Result(reviewId, orderId, reviewerId, revieweeId, rating, reviewText, now);
    }

    private static void validate(UUID orderId, UUID reviewerId, int rating, String text) {
        if (orderId == null || reviewerId == null) throw new IllegalArgumentException("评价主体不能为空");
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("评分必须在 1 到 5 之间");
        if (text == null || text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("评价内容无效");
    }

    private record OrderFacts(UUID buyerId, UUID sellerId, String status) { }
    public record Result(UUID reviewId, UUID orderId, UUID reviewerId, UUID revieweeId,
                         int rating, String reviewText, Instant createdAt) { }
    public static class NotFoundException extends RuntimeException { }
    public static class ConflictException extends RuntimeException { }
    public static class UnprocessableException extends RuntimeException { }
}
