package com.example.campusmarket.dispute.application;

import com.example.campusmarket.dispute.domain.ReturnProofType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 普通争议截止任务；MySQL 时间、租约和 claim token 共同提供 fencing。 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "campus.market.dispute.deadline", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public final class DisputeDeadlineScheduler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String owner = "dispute-deadline-" + UUID.randomUUID();

    public DisputeDeadlineScheduler(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    @Scheduled(initialDelayString = "${campus.market.dispute.deadline.initial-delay-ms:0}", fixedDelayString = "${campus.market.dispute.deadline.fixed-delay-ms:1000}")
    public void dispatch() { runOnce(50); }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("争议截止任务批量大小必须在1到1000之间");
        List<UUID> ids = jdbc.query("SELECT id FROM dispute_deadline_claim WHERE due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6))) ORDER BY due_at,id LIMIT ?", (rs, n) -> UUID.fromString(rs.getString(1)), limit);
        int processed = 0;
        for (UUID id : ids) { ClaimHandle handle = claim(id); if (handle != null && process(handle)) processed++; }
        return processed;
    }

    public int runOne(UUID disputeCaseId) {
        Objects.requireNonNull(disputeCaseId, "争议ID不能为空");
        List<UUID> ids = jdbc.query("SELECT id FROM dispute_deadline_claim WHERE dispute_case_id=? AND due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6))) ORDER BY due_at,id", (rs, n) -> UUID.fromString(rs.getString(1)), disputeCaseId.toString());
        int processed = 0;
        for (UUID id : ids) { ClaimHandle handle = claim(id); if (handle != null && process(handle)) processed++; }
        return processed;
    }

    private ClaimHandle claim(UUID id) {
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE dispute_deadline_claim SET status='PROCESSING',owner_id=?,claim_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND),attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))",
            owner, token, id.toString());
        return changed == 1 ? new ClaimHandle(id, owner, token) : null;
    }

    private boolean process(ClaimHandle handle) {
        try {
            Boolean result = transactions.execute(status -> processClaim(handle));
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE dispute_deadline_claim SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)", handle.id().toString(), handle.owner(), handle.token());
            return false;
        }
    }

    private boolean processClaim(ClaimHandle handle) {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
        Claim claim = jdbc.query("SELECT dispute_case_id,deadline_type,due_at,owner_id,claim_token,lease_until FROM dispute_deadline_claim WHERE id=? FOR UPDATE", rs -> rs.next()
            ? new Claim(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()) : null, handle.id().toString());
        if (claim == null || !handle.owner().equals(claim.owner()) || !handle.token().equals(claim.token()) || !claim.leaseUntil().isAfter(now)) return false;
        var caseRow = jdbc.query("SELECT c.status,c.seller_deadline,c.admin_deadline,c.hard_deadline,c.proof_type,c.proof_reference,c.disputed_quantity,c.order_id,o.listing_id,o.unit_price_fen,p.id,p.paid_amount_fen,p.provider,p.status "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id LEFT JOIN payment_order p ON p.order_id=o.id AND p.status='SUCCEEDED' "
                + "WHERE c.id=? FOR UPDATE", rs -> rs.next() ? new CaseFacts(rs.getString(1), ts(rs.getTimestamp(2)), ts(rs.getTimestamp(3)), ts(rs.getTimestamp(4)),
            rs.getString(5), rs.getString(6), rs.getInt(7), UUID.fromString(rs.getString(8)), UUID.fromString(rs.getString(9)), rs.getLong(10),
            rs.getString(11) == null ? null : UUID.fromString(rs.getString(11)), rs.getLong(12), rs.getString(13)) : null, claim.caseId().toString());
        if (caseRow == null) return complete(handle, claim);
        switch (claim.type()) {
            case "SELLER_RESPONSE" -> sellerDeadline(caseRow, now);
            case "ADMIN_SLA" -> adminSla(caseRow, now);
            case "HARD_DEADLINE" -> hardDeadline(caseRow, now);
            default -> throw new IllegalArgumentException("未知争议截止类型");
        }
        return complete(handle, claim);
    }

    private void sellerDeadline(CaseFacts row, Instant now) {
        if ("OPEN".equals(row.status()) && row.sellerDeadline() != null && !now.isBefore(row.sellerDeadline())) {
            jdbc.update("UPDATE dispute_case SET status='UNDER_REVIEW',admin_deadline=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE order_id=? AND status='OPEN'",
                row.orderId().toString());
        }
    }

    private void adminSla(CaseFacts row, Instant now) {
        if (!terminal(row.status()) && row.adminDeadline() != null && !now.isBefore(row.adminDeadline())) {
            jdbc.update("UPDATE dispute_case SET admin_sla_alerted_at=COALESCE(admin_sla_alerted_at,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", row.orderId().toString());
            insertEvent("DISPUTE_SLA_ALERT", row.orderId(), row.orderId().toString(), "adminDeadline");
        }
    }

    private void hardDeadline(CaseFacts row, Instant now) {
        if (terminal(row.status())) return;
        boolean trusted = row.proofType() != null && ReturnProofType.parse(row.proofType()).isTrusted();
        if (!trusted || row.paymentId() == null || row.paidAmountFen() < amount(row.unitPriceFen(), row.disputedQuantity())) {
            jdbc.update("UPDATE dispute_case SET status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW')", row.orderId().toString());
            return;
        }
        long amount = amount(row.unitPriceFen(), row.disputedQuantity());
        boolean reserved = jdbc.update("UPDATE payment_order SET reserved_refund_fen=reserved_refund_fen+?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='SUCCEEDED' AND successful_refund_fen+reserved_refund_fen+?<=paid_amount_fen", amount, row.paymentId().toString(), amount) == 1;
        if (!reserved) {
            jdbc.update("UPDATE dispute_case SET status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW')", row.orderId().toString());
            return;
        }
        UUID refundId = UUID.nameUUIDFromBytes((row.orderId() + "|dispute-hard-refund").getBytes(StandardCharsets.UTF_8));
        String key = "dispute-hard-refund-" + row.orderId();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,paid_amount_fen,amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'DISPUTE',?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
            refundId.toString(), row.orderId().toString(), row.paymentId().toString(), row.provider(), key, row.paymentId().toString(), row.paidAmountFen(), amount, "REQUESTED");
        UUID eventId = UUID.nameUUIDFromBytes(("dispute-refund:" + refundId).getBytes(StandardCharsets.UTF_8));
        String payload = "{\"refundId\":\"" + refundId + "\",\"orderId\":\"" + row.orderId() + "\",\"amountFen\":" + amount + "}";
        jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?,?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
            eventId.toString(), eventId.toString(), "REFUND_REQUESTED", refundId.toString(), 1L, payload);
        jdbc.update("UPDATE dispute_case SET status='RESOLVED',decision='REFUND_ONLY',approved_quantity=?,resolved_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW')", row.disputedQuantity(), row.orderId().toString());
    }

    private boolean complete(ClaimHandle handle, Claim claim) {
        return jdbc.update("UPDATE dispute_deadline_claim SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6),owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)", handle.id().toString(), handle.owner(), handle.token()) == 1;
    }

    private void insertEvent(String type, UUID aggregateId, String orderId, String field) {
        UUID eventId = UUID.nameUUIDFromBytes((type + ":" + orderId).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?,?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
            eventId.toString(), eventId.toString(), type, aggregateId.toString(), 1L, "{\"orderId\":\"" + orderId + "\",\"field\":\"" + field + "\"}");
    }

    private static boolean terminal(String status) { return "RESOLVED".equals(status) || "REJECTED".equals(status); }
    private static long amount(long unit, int quantity) { try { return Math.multiplyExact(unit, quantity); } catch (ArithmeticException ex) { return Long.MAX_VALUE; } }
    private static Instant ts(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record Claim(UUID caseId, String type, Instant dueAt, String owner, String token, Instant leaseUntil) {}
    private record ClaimHandle(UUID id, String owner, String token) {}
    private record CaseFacts(String status, Instant sellerDeadline, Instant adminDeadline, Instant hardDeadline, String proofType,
                             String proofReference, int disputedQuantity, UUID orderId, UUID listingId, long unitPriceFen,
                             UUID paymentId, long paidAmountFen, String provider) {}
}
