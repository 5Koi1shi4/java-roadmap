package com.example.campusmarket.order.infrastructure;

import com.example.campusmarket.order.domain.OrderStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 订单生命周期的 MySQL 事实源；所有写入均由状态、版本和截止时间条件保护。 */
@Repository
public class JdbcOrderLifecycleRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcOrderLifecycleRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.mapper = Objects.requireNonNull(mapper, "JSON序列化器不能为空");
    }

    public Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    public OrderRow lock(UUID orderId) {
        return jdbc.query("""
                SELECT id,buyer_id,seller_id,listing_id,quantity,paid_amount_fen,status,version,
                       payment_deadline,handoff_deadline,receipt_deadline,t0,acceptance_deadline,trial_deadline,warranty_deadline
                FROM trade_order WHERE id=? FOR UPDATE
                """,
            rs -> rs.next() ? new OrderRow(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("buyer_id")), UUID.fromString(rs.getString("seller_id")),
                UUID.fromString(rs.getString("listing_id")), rs.getInt("quantity"), rs.getLong("paid_amount_fen"),
                OrderStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                instant(rs.getTimestamp("payment_deadline")), instant(rs.getTimestamp("handoff_deadline")),
                instant(rs.getTimestamp("receipt_deadline")), instant(rs.getTimestamp("t0")),
                instant(rs.getTimestamp("acceptance_deadline")), instant(rs.getTimestamp("trial_deadline")),
                instant(rs.getTimestamp("warranty_deadline"))) : null, orderId.toString());
    }

    public int transition(UUID orderId, OrderStatus from, OrderStatus to, long expectedVersion,
                          Instant databaseNow, String deadlineColumn, boolean requireBeforeDeadline,
                          Instant newT0, Instant newAcceptanceDeadline, Instant newTrialDeadline,
                          String reason) {
        if (!OrderStatusSafe.isValidColumn(deadlineColumn)) throw new IllegalArgumentException("截止时间列无效");
        String deadlinePredicate = deadlineColumn == null ? "" : " AND " + deadlineColumn + (requireBeforeDeadline ? " > ?" : " <= ?");
        String sql = "UPDATE trade_order SET status=?,version=version+1,updated_at=?"
            + (newT0 == null ? "" : ",t0=?")
            + (newAcceptanceDeadline == null ? "" : ",acceptance_deadline=?")
            + (newTrialDeadline == null ? "" : ",trial_deadline=?")
            + " WHERE id=? AND status=? AND version=?" + deadlinePredicate;
        List<Object> args = new ArrayList<>();
        args.add(to.name()); args.add(timestamp(databaseNow));
        if (newT0 != null) args.add(timestamp(newT0));
        if (newAcceptanceDeadline != null) args.add(timestamp(newAcceptanceDeadline));
        if (newTrialDeadline != null) args.add(timestamp(newTrialDeadline));
        args.add(orderId.toString()); args.add(from.name()); args.add(expectedVersion);
        if (deadlineColumn != null) args.add(timestamp(databaseNow));
        int changed = jdbc.update(sql, args.toArray());
        if (changed == 1) {
            long newVersion = expectedVersion + 1;
            insertTransition(orderId, from, to, reason, databaseNow);
            insertOutbox(orderId, to, newVersion, databaseNow, reason);
        }
        return changed;
    }

    public int markHandoff(UUID orderId, UUID sellerId, long expectedVersion, Instant databaseNow,
                           String note, Instant receiptDeadline) {
        int changed = jdbc.update("UPDATE trade_order SET status='AWAITING_RECEIPT',version=version+1,receipt_deadline=?,updated_at=? "
                + "WHERE id=? AND seller_id=? AND status='AWAITING_HANDOFF' AND version=? AND handoff_deadline > ?",
            timestamp(receiptDeadline), timestamp(databaseNow), orderId.toString(), sellerId.toString(), expectedVersion,
            timestamp(databaseNow));
        if (changed == 1) {
            long v = expectedVersion + 1;
            jdbc.update("INSERT INTO handoff_record (id,order_id,actor_id,note,handed_off_at,created_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), orderId.toString(), sellerId.toString(), note,
                timestamp(databaseNow), timestamp(databaseNow));
            insertDeadline(orderId, "RECEIPT", receiptDeadline);
            insertTransition(orderId, OrderStatus.AWAITING_HANDOFF, OrderStatus.AWAITING_RECEIPT, "SELLER_HANDOFF", databaseNow);
            insertOutbox(orderId, OrderStatus.AWAITING_RECEIPT, v, databaseNow, "SELLER_HANDOFF");
        }
        return changed;
    }

    public int confirmReceipt(UUID orderId, UUID buyerId, long expectedVersion, Instant databaseNow,
                              Instant acceptanceDeadline, Instant trialDeadline) {
        int changed = jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW',version=version+1,t0=?,acceptance_deadline=?,trial_deadline=?,updated_at=? "
                + "WHERE id=? AND buyer_id=? AND status='AWAITING_RECEIPT' AND version=? AND receipt_deadline > ?",
            timestamp(databaseNow), timestamp(acceptanceDeadline), timestamp(trialDeadline), timestamp(databaseNow),
            orderId.toString(), buyerId.toString(), expectedVersion, timestamp(databaseNow));
        if (changed == 1) {
            long v = expectedVersion + 1;
            insertDeadline(orderId, "TRIAL", trialDeadline);
            insertTransition(orderId, OrderStatus.AWAITING_RECEIPT, OrderStatus.AFTERSALE_WINDOW, "BUYER_RECEIPT", databaseNow);
            insertOutbox(orderId, OrderStatus.AFTERSALE_WINDOW, v, databaseNow, "BUYER_RECEIPT");
        }
        return changed;
    }

    public int confirmReceiptAutomatically(UUID orderId, long expectedVersion, Instant databaseNow,
                                           Instant acceptanceDeadline, Instant trialDeadline) {
        int changed = jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW',version=version+1,t0=?,acceptance_deadline=?,trial_deadline=?,updated_at=? "
                + "WHERE id=? AND status='AWAITING_RECEIPT' AND version=? AND receipt_deadline <= ?",
            timestamp(databaseNow), timestamp(acceptanceDeadline), timestamp(trialDeadline), timestamp(databaseNow),
            orderId.toString(), expectedVersion, timestamp(databaseNow));
        if (changed == 1) {
            long v = expectedVersion + 1;
            insertDeadline(orderId, "TRIAL", trialDeadline);
            insertTransition(orderId, OrderStatus.AWAITING_RECEIPT, OrderStatus.AFTERSALE_WINDOW, "AUTO_RECEIPT", databaseNow);
            insertOutbox(orderId, OrderStatus.AFTERSALE_WINDOW, v, databaseNow, "AUTO_RECEIPT");
        }
        return changed;
    }

    public void insertDeadline(UUID orderId, String type, Instant dueAt) {
        jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) VALUES (?,?,?,'NEW',?) "
                + "ON DUPLICATE KEY UPDATE due_at=VALUES(due_at)", UUID.randomUUID().toString(), orderId.toString(), type, timestamp(dueAt));
    }

    @Transactional
    public List<DeadlineClaim> claimBatch(String owner, int limit, Duration lease) {
        if (owner == null || owner.isBlank() || limit <= 0 || limit > 1000 || lease == null || lease.isNegative() || lease.isZero())
            throw new IllegalArgumentException("截止任务领取参数无效");
        Instant now = databaseNow();
        List<DeadlineClaim> candidates = jdbc.query("SELECT id,order_id,deadline_type,due_at FROM order_deadline_claim "
                + "WHERE (status='NEW' AND due_at <= ?) OR (status='PROCESSING' AND lease_until <= ?) "
                + "ORDER BY due_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs, row) -> new DeadlineClaim(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("deadline_type"), rs.getTimestamp("due_at").toInstant(), owner, null, null),
            timestamp(now), timestamp(now), limit);
        List<DeadlineClaim> claimed = new ArrayList<>();
        long micros = Math.max(1, lease.toNanos() / 1_000L);
        for (DeadlineClaim candidate : candidates) {
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE order_deadline_claim SET status='PROCESSING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(MICROSECOND, ?, ?),attempt_count=attempt_count+1 "
                    + "WHERE id=? AND ((status='NEW' AND due_at <= ?) OR (status='PROCESSING' AND lease_until <= ?))",
                owner, token, micros, timestamp(now), candidate.id().toString(), timestamp(now), timestamp(now));
            if (changed == 1) claimed.add(candidate.withToken(token, now.plus(lease)));
        }
        return List.copyOf(claimed);
    }

    public int completeClaim(DeadlineClaim claim, Instant now) {
        return jdbc.update("UPDATE order_deadline_claim SET status='COMPLETED',owner_id=NULL,claim_token=NULL,lease_until=NULL,completed_at=? "
                + "WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            timestamp(now), claim.id().toString(), claim.owner(), claim.token());
    }

    /** 在执行截止动作前锁定 claim 行，防止租约过期后的旧 owner 继续写订单。 */
    public boolean lockOwnedClaim(DeadlineClaim claim, Instant now) {
        Boolean owned = jdbc.query("SELECT id FROM order_deadline_claim WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=? AND lease_until > ? FOR UPDATE",
            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(),
            claim.id().toString(), claim.owner(), claim.token(), timestamp(now));
        return Boolean.TRUE.equals(owned);
    }

    public int failClaim(DeadlineClaim claim, Instant now) {
        return jdbc.update("UPDATE order_deadline_claim SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL "
                + "WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            claim.id().toString(), claim.owner(), claim.token());
    }

    private void insertTransition(UUID orderId, OrderStatus from, OrderStatus to, String reason, Instant at) {
        jdbc.update("INSERT INTO order_transition (id,order_id,from_status,to_status,reason,occurred_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID().toString(), orderId.toString(), from.name(), to.name(), reason, timestamp(at));
    }

    private void insertOutbox(UUID orderId, OrderStatus status, long version, Instant at, String reason) {
        String payload;
        try { payload = mapper.writeValueAsString(Map.of("orderId", orderId.toString(), "status", status.name(), "reason", reason)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("订单事件序列化失败", e); }
        jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) "
                + "VALUES (?,?,?,?,?,1,?,CAST(? AS JSON),'NEW',0,?,?)",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType(status, reason), orderId.toString(), version,
            timestamp(at), payload, timestamp(at), timestamp(at));
    }

    private static String eventType(OrderStatus status, String reason) {
        return switch (reason) {
            case "SELLER_HANDOFF" -> "ORDER_HANDOFF_CONFIRMED";
            case "BUYER_RECEIPT", "AUTO_RECEIPT" -> "ORDER_RECEIPT_CONFIRMED";
            case "HANDOFF_TIMEOUT" -> "ORDER_REFUNDING_CANCEL";
            case "TRIAL_CLOSED" -> "ORDER_SETTLED";
            case "PAYMENT_TIMEOUT" -> "ORDER_CANCELLED";
            default -> "ORDER_" + status.name();
        };
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record OrderRow(UUID id, UUID buyerId, UUID sellerId, UUID listingId, int quantity, long paidAmountFen,
                           OrderStatus status, long version, Instant paymentDeadline, Instant handoffDeadline,
                           Instant receiptDeadline, Instant t0, Instant acceptanceDeadline, Instant trialDeadline,
                           Instant warrantyDeadline) {}

    public record DeadlineClaim(UUID id, UUID orderId, String type, Instant dueAt, String owner, String token,
                                Instant leaseUntil) {
        private DeadlineClaim withToken(String value, Instant until) { return new DeadlineClaim(id, orderId, type, dueAt, owner, value, until); }
    }

    private static final class OrderStatusSafe {
        private static final java.util.Set<String> COLUMNS = java.util.Set.of("payment_deadline", "handoff_deadline", "receipt_deadline", "acceptance_deadline", "trial_deadline", "warranty_deadline");
        private static boolean isValidColumn(String value) { return value == null || COLUMNS.contains(value); }
    }
}
