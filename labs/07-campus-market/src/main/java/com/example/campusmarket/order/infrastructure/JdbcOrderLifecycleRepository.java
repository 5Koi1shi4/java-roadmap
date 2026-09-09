package com.example.campusmarket.order.infrastructure;

import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.domain.OrderStatusTransitions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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

    /** Rabbit 重放只能收敛已由支付聚合确认的成功事实。 */
    public boolean hasMatchingSuccessfulPayment(UUID orderId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE order_id=? AND status='SUCCEEDED' "
            + "AND provider IS NOT NULL AND provider_reference IS NOT NULL AND paid_amount_fen=amount_fen AND amount_fen>0",
            Integer.class, orderId.toString());
        return count != null && count > 0;
    }

    public int transition(UUID orderId, OrderStatus from, OrderStatus to, long expectedVersion,
                          Instant databaseNow, String deadlineColumn, boolean requireBeforeDeadline,
                          Instant newT0, Instant newAcceptanceDeadline, Instant newTrialDeadline,
                          String reason) {
        return transition(orderId, from, to, expectedVersion, databaseNow, deadlineColumn, requireBeforeDeadline,
            newT0, newAcceptanceDeadline, newTrialDeadline, reason, null);
    }

    public int transition(UUID orderId, OrderStatus from, OrderStatus to, long expectedVersion,
                          Instant databaseNow, String deadlineColumn, boolean requireBeforeDeadline,
                          Instant newT0, Instant newAcceptanceDeadline, Instant newTrialDeadline,
                          String reason, UUID actorId) {
        if (!OrderStatusTransitions.isAllowed(from, to)) throw new IllegalArgumentException("非法订单状态迁移");
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
            insertTransition(orderId, from, to, reason, databaseNow, actorId);
            insertOutbox(orderId, to, newVersion, databaseNow, reason);
        }
        return changed;
    }

    public int markHandoff(UUID orderId, UUID sellerId, long expectedVersion, Instant databaseNow,
                           String note, Instant receiptDeadline) {
        return markHandoff(orderId, sellerId, expectedVersion, databaseNow, note, receiptDeadline, sellerId);
    }

    public int markHandoff(UUID orderId, UUID sellerId, long expectedVersion, Instant databaseNow,
                           String note, Instant receiptDeadline, UUID actorId) {
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
            insertTransition(orderId, OrderStatus.AWAITING_HANDOFF, OrderStatus.AWAITING_RECEIPT, "SELLER_HANDOFF", databaseNow, actorId);
            insertOutbox(orderId, OrderStatus.AWAITING_RECEIPT, v, databaseNow, "SELLER_HANDOFF");
        }
        return changed;
    }

    public int confirmReceipt(UUID orderId, UUID buyerId, long expectedVersion, Instant databaseNow,
                              Instant acceptanceDeadline, Instant trialDeadline) {
        return confirmReceipt(orderId, buyerId, expectedVersion, databaseNow, acceptanceDeadline, trialDeadline, buyerId);
    }

    public int confirmReceipt(UUID orderId, UUID buyerId, long expectedVersion, Instant databaseNow,
                              Instant acceptanceDeadline, Instant trialDeadline, UUID actorId) {
        int changed = jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW',version=version+1,t0=?,acceptance_deadline=?,trial_deadline=?,updated_at=? "
                + "WHERE id=? AND buyer_id=? AND status='AWAITING_RECEIPT' AND version=? AND receipt_deadline > ? AND t0 IS NULL AND acceptance_deadline IS NULL AND trial_deadline IS NULL",
            timestamp(databaseNow), timestamp(acceptanceDeadline), timestamp(trialDeadline), timestamp(databaseNow),
            orderId.toString(), buyerId.toString(), expectedVersion, timestamp(databaseNow));
        if (changed == 1) {
            long v = expectedVersion + 1;
            insertDeadline(orderId, "TRIAL", trialDeadline);
            insertTransition(orderId, OrderStatus.AWAITING_RECEIPT, OrderStatus.AFTERSALE_WINDOW, "BUYER_RECEIPT", databaseNow, actorId);
            insertOutbox(orderId, OrderStatus.AFTERSALE_WINDOW, v, databaseNow, "BUYER_RECEIPT");
        }
        return changed;
    }

    public int confirmReceiptAutomatically(UUID orderId, long expectedVersion, Instant databaseNow,
                                           Instant acceptanceDeadline, Instant trialDeadline) {
        int changed = jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW',version=version+1,t0=?,acceptance_deadline=?,trial_deadline=?,updated_at=? "
                + "WHERE id=? AND status='AWAITING_RECEIPT' AND version=? AND receipt_deadline <= ? AND t0 IS NULL AND acceptance_deadline IS NULL AND trial_deadline IS NULL",
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
        List<DeadlineClaim> candidates = jdbc.query("SELECT id,order_id,deadline_type,due_at,attempt_count FROM order_deadline_claim "
                + "WHERE (status='NEW' AND due_at <= ?) OR (status='PROCESSING' AND lease_until <= ?) "
                + "ORDER BY due_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs, row) -> new DeadlineClaim(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("deadline_type"), rs.getTimestamp("due_at").toInstant(), owner, null, null,
                rs.getInt("attempt_count")),
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

    /** 按订单定向领取，避免旧 due 行占满批次而使指定订单饥饿。 */
    @Transactional
    public DeadlineClaim claimOne(UUID orderId, String owner, Duration lease) {
        if (orderId == null || owner == null || owner.isBlank() || lease == null || lease.isNegative() || lease.isZero())
            throw new IllegalArgumentException("截止任务定向领取参数无效");
        Instant now = databaseNow();
        DeadlineClaim candidate = jdbc.query("SELECT id,order_id,deadline_type,due_at,attempt_count FROM order_deadline_claim "
                + "WHERE order_id=? AND ((status='NEW' AND due_at <= ?) OR (status='PROCESSING' AND lease_until <= ?)) "
                + "ORDER BY due_at,id LIMIT 1 FOR UPDATE",
            rs -> rs.next() ? new DeadlineClaim(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("deadline_type"), rs.getTimestamp("due_at").toInstant(), owner, null, null,
                rs.getInt("attempt_count")) : null,
            orderId.toString(), timestamp(now), timestamp(now));
        if (candidate == null) return null;
        String token = UUID.randomUUID().toString();
        long micros = Math.max(1, lease.toNanos() / 1_000L);
        int changed = jdbc.update("UPDATE order_deadline_claim SET status='PROCESSING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(MICROSECOND, ?, ?),attempt_count=attempt_count+1 "
                + "WHERE id=? AND ((status='NEW' AND due_at <= ?) OR (status='PROCESSING' AND lease_until <= ?))",
            owner, token, micros, timestamp(now), candidate.id().toString(), timestamp(now), timestamp(now));
        return changed == 1 ? candidate.withToken(token, now.plus(lease)) : null;
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

    /** 失败后短退避重试；第三次失败原子进入 FAILED 并记录人工告警事实。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retryOrFailClaim(DeadlineClaim claim, String failureMessage) {
        Objects.requireNonNull(claim, "截止领取不能为空");
        String message = failureMessage == null || failureMessage.isBlank() ? "deadline action failed" : failureMessage;
        Instant now = databaseNow();
        if (claim.attemptCount() >= 3) {
            String payload = "{\"orderId\":\"" + claim.orderId() + "\",\"deadlineType\":\"" + claim.type()
                + "\",\"claimId\":\"" + claim.id() + "\",\"error\":\"" + jsonEscape(message) + "\"}";
            int fact = jdbc.update("INSERT INTO manual_failure (id,source_type,source_id,consumer_name,failure_class,payload,status,created_at) "
                    + "SELECT ?, 'ORDER_DEADLINE', ?, 'deadline-scheduler', 'EXHAUSTED', CAST(? AS JSON), 'NEW', ? FROM order_deadline_claim "
                    + "WHERE order_deadline_claim.id=? AND order_deadline_claim.status='PROCESSING' AND order_deadline_claim.owner_id=? "
                    + "AND order_deadline_claim.claim_token=? AND order_deadline_claim.attempt_count>=3 "
                    + "ON DUPLICATE KEY UPDATE id=manual_failure.id",
                UUID.randomUUID().toString(), claim.id().toString(), payload, timestamp(now), claim.id().toString(), claim.owner(), claim.token());
            if (fact == 0) {
                Boolean existing = jdbc.query("SELECT id FROM manual_failure WHERE source_type='ORDER_DEADLINE' AND source_id=? AND consumer_name='deadline-scheduler'",
                    (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), claim.id().toString());
                if (!Boolean.TRUE.equals(existing)) return 0;
            }
            return jdbc.update("UPDATE order_deadline_claim SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL,completed_at=? WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=? AND attempt_count>=3",
                timestamp(now), claim.id().toString(), claim.owner(), claim.token());
        }
        long delayMicros = 250_000L * (1L << Math.max(0, claim.attemptCount() - 1));
        return jdbc.update("UPDATE order_deadline_claim SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,due_at=TIMESTAMPADD(MICROSECOND,?,?) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            delayMicros, timestamp(now), claim.id().toString(), claim.owner(), claim.token());
    }

    public int markTrialElapsed(UUID orderId, long expectedVersion, Instant databaseNow) {
        int changed = jdbc.update("UPDATE trade_order SET version=version+1,updated_at=? WHERE id=? AND status='AFTERSALE_WINDOW' AND version=? AND trial_deadline<=? AND t0 IS NOT NULL",
            timestamp(databaseNow), orderId.toString(), expectedVersion, timestamp(databaseNow));
        if (changed == 1) {
            insertOutbox(orderId, OrderStatus.AFTERSALE_WINDOW, expectedVersion + 1, databaseNow, "TRIAL_ELAPSED");
        }
        return changed;
    }

    private void insertTransition(UUID orderId, OrderStatus from, OrderStatus to, String reason, Instant at) {
        insertTransition(orderId, from, to, reason, at, null);
    }

    private void insertTransition(UUID orderId, OrderStatus from, OrderStatus to, String reason, Instant at, UUID actorId) {
        jdbc.update("INSERT INTO order_transition (id,order_id,from_status,to_status,actor_id,reason,occurred_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), orderId.toString(), from.name(), to.name(), actorId == null ? null : actorId.toString(), reason, timestamp(at));
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
            case "PAYMENT_SUCCEEDED" -> "ORDER_PAID";
            case "HANDOFF_TIMEOUT" -> "ORDER_REFUNDING_CANCEL";
            case "TRIAL_ELAPSED" -> "ORDER_TRIAL_ELAPSED";
            case "PAYMENT_TIMEOUT" -> "ORDER_CANCELLED";
            default -> "ORDER_" + status.name();
        };
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record OrderRow(UUID id, UUID buyerId, UUID sellerId, UUID listingId, int quantity, long paidAmountFen,
                           OrderStatus status, long version, Instant paymentDeadline, Instant handoffDeadline,
                           Instant receiptDeadline, Instant t0, Instant acceptanceDeadline, Instant trialDeadline,
                           Instant warrantyDeadline) {}

    public record DeadlineClaim(UUID id, UUID orderId, String type, Instant dueAt, String owner, String token,
                                Instant leaseUntil, int attemptCount) {
        public DeadlineClaim(UUID id, UUID orderId, String type, Instant dueAt, String owner, String token,
                             Instant leaseUntil) {
            this(id, orderId, type, dueAt, owner, token, leaseUntil, 1);
        }
        private DeadlineClaim withToken(String value, Instant until) {
            return new DeadlineClaim(id, orderId, type, dueAt, owner, value, until, attemptCount + 1);
        }
    }

    private static final class OrderStatusSafe {
        private static final java.util.Set<String> COLUMNS = java.util.Set.of("payment_deadline", "handoff_deadline", "receipt_deadline", "acceptance_deadline", "trial_deadline", "warranty_deadline");
        private static boolean isValidColumn(String value) { return value == null || COLUMNS.contains(value); }
    }
}
