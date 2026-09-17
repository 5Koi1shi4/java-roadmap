package com.example.campusmarket.support;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 支持状态摘要的只读 JDBC 访问。
 *
 * <p>每条查询都明确包含参与者谓词，调用方不会先拿到未授权行再由应用服务过滤。</p>
 */
@Repository
@Profile("!test")
public class JdbcSupportStatusRepository {
    private final JdbcTemplate jdbc;

    public JdbcSupportStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public SupportStatusService.StatusView order(UUID id, UUID userId) {
        return jdbc.query(
            "SELECT id,status,created_at,payment_deadline FROM trade_order "
                + "WHERE id=? AND (buyer_id=? OR seller_id=?)",
            rs -> rs.next() ? map(rs, "orders") : null,
            id.toString(), userId.toString(), userId.toString());
    }

    public List<SupportStatusService.StatusView> orders(UUID userId, Instant before,
                                                        UUID beforeId, int limit) {
        if (before == null && beforeId != null || before != null && beforeId == null) {
            throw new IllegalArgumentException("游标位置不完整");
        }
        if (before == null) {
            return jdbc.query(
                "SELECT id,status,created_at,payment_deadline FROM trade_order "
                    + "WHERE (buyer_id=? OR seller_id=?) "
                    + "ORDER BY created_at DESC,id DESC LIMIT ?",
                (rs, rowNum) -> map(rs, "orders"),
                userId.toString(), userId.toString(), limit + 1);
        }
        return jdbc.query(
            "SELECT id,status,created_at,payment_deadline FROM trade_order "
                + "WHERE (buyer_id=? OR seller_id=?) "
                + "AND (created_at<? OR (created_at=? AND id<?)) "
                + "ORDER BY created_at DESC,id DESC LIMIT ?",
            (rs, rowNum) -> map(rs, "orders"),
            userId.toString(), userId.toString(), Timestamp.from(before),
            Timestamp.from(before), beforeId.toString(), limit + 1);
    }

    public SupportStatusService.StatusView dispute(UUID id, UUID userId) {
        return jdbc.query(
            "SELECT c.id,c.status,c.created_at,c.seller_deadline "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id "
                + "WHERE c.id=? AND (o.buyer_id=? OR o.seller_id=?)",
            rs -> rs.next() ? map(rs, "disputes") : null,
            id.toString(), userId.toString(), userId.toString());
    }

    public List<SupportStatusService.StatusView> disputes(UUID userId, Instant before,
                                                           UUID beforeId, int limit) {
        if (before == null && beforeId != null || before != null && beforeId == null) {
            throw new IllegalArgumentException("游标位置不完整");
        }
        if (before == null) {
            return jdbc.query(
                "SELECT c.id,c.status,c.created_at,c.seller_deadline "
                    + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id "
                    + "WHERE (o.buyer_id=? OR o.seller_id=?) "
                    + "ORDER BY c.created_at DESC,c.id DESC LIMIT ?",
                (rs, rowNum) -> map(rs, "disputes"),
                userId.toString(), userId.toString(), limit + 1);
        }
        return jdbc.query(
            "SELECT c.id,c.status,c.created_at,c.seller_deadline "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id "
                + "WHERE (o.buyer_id=? OR o.seller_id=?) "
                + "AND (c.created_at<? OR (c.created_at=? AND c.id<?)) "
                + "ORDER BY c.created_at DESC,c.id DESC LIMIT ?",
            (rs, rowNum) -> map(rs, "disputes"),
            userId.toString(), userId.toString(), Timestamp.from(before),
            Timestamp.from(before), beforeId.toString(), limit + 1);
    }

    public SupportStatusService.StatusView warranty(UUID id, UUID userId) {
        return jdbc.query(
            "SELECT id,status,created_at,seller_deadline FROM warranty_case "
                + "WHERE id=? AND (buyer_id=? OR seller_id=?)",
            rs -> rs.next() ? map(rs, "warranties") : null,
            id.toString(), userId.toString(), userId.toString());
    }

    public List<SupportStatusService.StatusView> warranties(UUID userId, Instant before,
                                                             UUID beforeId, int limit) {
        if (before == null && beforeId != null || before != null && beforeId == null) {
            throw new IllegalArgumentException("游标位置不完整");
        }
        if (before == null) {
            return jdbc.query(
                "SELECT id,status,created_at,seller_deadline FROM warranty_case "
                    + "WHERE (buyer_id=? OR seller_id=?) "
                    + "ORDER BY created_at DESC,id DESC LIMIT ?",
                (rs, rowNum) -> map(rs, "warranties"),
                userId.toString(), userId.toString(), limit + 1);
        }
        return jdbc.query(
            "SELECT id,status,created_at,seller_deadline FROM warranty_case "
                + "WHERE (buyer_id=? OR seller_id=?) "
                + "AND (created_at<? OR (created_at=? AND id<?)) "
                + "ORDER BY created_at DESC,id DESC LIMIT ?",
            (rs, rowNum) -> map(rs, "warranties"),
            userId.toString(), userId.toString(), Timestamp.from(before),
            Timestamp.from(before), beforeId.toString(), limit + 1);
    }

    private static SupportStatusService.StatusView map(java.sql.ResultSet rs, String type)
        throws java.sql.SQLException {
        return new SupportStatusService.StatusView(
            UUID.fromString(rs.getString("id")),
            type,
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            timestamp(rs.getTimestamp(type.equals("orders") ? "payment_deadline" : "seller_deadline")));
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
