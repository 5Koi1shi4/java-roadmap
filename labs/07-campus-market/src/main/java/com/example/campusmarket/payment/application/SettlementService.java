package com.example.campusmarket.payment.application;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 普通争议净结算；质保期限不参与这条七天结算门禁。 */
@Service
@Profile("!test")
public class SettlementService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SettlementService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    public SettlementResult settle(UUID orderId) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        return transactions.execute(status -> settleLocked(orderId));
    }

    public boolean canSettle(UUID orderId) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        return transactions.execute(status -> {
            var row = lockOrder(orderId);
            return row != null && eligible(row, databaseNow());
        });
    }

    private SettlementResult settleLocked(UUID orderId) {
        OrderFacts order = lockOrder(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if ("SETTLED".equals(order.status())) return existing(orderId);
        Instant now = databaseNow();
        if (!eligible(order, now)) return new SettlementResult(orderId, "BLOCKED", 0L, blockReason(order, now));
        PaymentFacts payment = jdbc.query("SELECT COALESCE(MAX(paid_amount_fen),0),COALESCE(MAX(successful_refund_fen),0),COALESCE(MAX(reserved_refund_fen),0) FROM payment_order WHERE order_id=? AND status='SUCCEEDED'",
            rs -> rs.next() ? new PaymentFacts(rs.getLong(1), rs.getLong(2), rs.getLong(3)) : new PaymentFacts(0, 0, 0), orderId.toString());
        long net = payment.paidAmountFen() - payment.successfulRefundFen();
        if (net < 0) return new SettlementResult(orderId, "BLOCKED", 0L, "REFUND_EXCEEDS_PAID");
        UUID settlementId = UUID.nameUUIDFromBytes(("settlement:" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO settlement (id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at,settled_at) VALUES (?,?,?,?,?,'SETTLED',?,?) ON DUPLICATE KEY UPDATE status='SETTLED',net_settlement_fen=VALUES(net_settlement_fen),settled_at=VALUES(settled_at)",
            settlementId.toString(), orderId.toString(), payment.paidAmountFen(), payment.successfulRefundFen(), net, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("UPDATE trade_order SET status='SETTLED',version=version+1,updated_at=? WHERE id=? AND status='AFTERSALE_WINDOW'", Timestamp.from(now), orderId.toString());
        UUID eventId = UUID.nameUUIDFromBytes(("settlement-created:" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = "{\"settlementId\":\"" + settlementId + "\",\"orderId\":\"" + orderId
            + "\",\"netSettlementFen\":" + net + "}";
        jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) "
                + "VALUES (?,?, 'SETTLEMENT_CREATED',?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
            eventId.toString(), eventId.toString(), orderId.toString(), 1L, payload);
        return new SettlementResult(orderId, "SETTLED", net, null);
    }

    private boolean eligible(OrderFacts order, Instant now) {
        if (!"AFTERSALE_WINDOW".equals(order.status())) return false;
        if (order.t0() == null || now.isBefore(order.t0().plus(Duration.ofDays(7)))) return false;
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", Integer.class, order.id().toString());
        if (active != null && active > 0) return false;
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", Integer.class, order.id().toString());
        if (pending != null && pending > 0) return false;
        Integer unreconciled = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order f JOIN return_case r ON r.order_id=f.order_id AND (r.refund_id=f.id OR (r.refund_id IS NULL AND (f.idempotency_key=CONCAT('dispute-return-',r.dispute_case_id) OR f.idempotency_key=CONCAT('dispute-hard-refund-',r.dispute_case_id)))) WHERE f.order_id=? AND f.status='SUCCEEDED' AND (COALESCE(r.refund_status,'')<>'SUCCEEDED' OR (r.resolution_type='RETURN_AND_REFUND' AND r.quarantined_at IS NULL))", Integer.class, order.id().toString());
        if (unreconciled != null && unreconciled > 0) return false;
        Long reserved = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_refund_fen),0) FROM payment_order WHERE order_id=?", Long.class, order.id().toString());
        return reserved == null || reserved == 0;
    }

    private String blockReason(OrderFacts order, Instant now) {
        if (!"AFTERSALE_WINDOW".equals(order.status())) return "ORDER_STATUS";
        if (order.t0() == null || now.isBefore(order.t0().plus(Duration.ofDays(7)))) return "TRIAL_WINDOW";
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", Integer.class, order.id().toString());
        if (active != null && active > 0) return "ACTIVE_DISPUTE";
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", Integer.class, order.id().toString());
        if (pending != null && pending > 0) return "PENDING_REFUND";
        Integer unreconciled = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order f JOIN return_case r ON r.order_id=f.order_id AND (r.refund_id=f.id OR (r.refund_id IS NULL AND (f.idempotency_key=CONCAT('dispute-return-',r.dispute_case_id) OR f.idempotency_key=CONCAT('dispute-hard-refund-',r.dispute_case_id)))) WHERE f.order_id=? AND f.status='SUCCEEDED' AND (COALESCE(r.refund_status,'')<>'SUCCEEDED' OR (r.resolution_type='RETURN_AND_REFUND' AND r.quarantined_at IS NULL))", Integer.class, order.id().toString());
        if (unreconciled != null && unreconciled > 0) return "UNRECONCILED_RETURN";
        return "REFUND_RESERVATION";
    }

    private SettlementResult existing(UUID orderId) {
        return jdbc.query("SELECT net_settlement_fen,status FROM settlement WHERE order_id=?", rs -> rs.next()
            ? new SettlementResult(orderId, rs.getString(2), rs.getLong(1), null)
            : new SettlementResult(orderId, "SETTLED", 0, null), orderId.toString());
    }

    private OrderFacts lockOrder(UUID orderId) {
        return jdbc.query("SELECT id,status,t0 FROM trade_order WHERE id=? FOR UPDATE", rs -> rs.next()
            ? new OrderFacts(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()) : null, orderId.toString());
    }
    private Instant databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant(); }

    public record SettlementResult(UUID orderId, String status, long netSettlementFen, String blockedReason) {}
    private record OrderFacts(UUID id, String status, Instant t0) {}
    private record PaymentFacts(long paidAmountFen, long successfulRefundFen, long reservedRefundFen) {}
}
