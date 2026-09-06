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
    private final ReturnResolutionService resolutions;
    private final String owner = "dispute-deadline-" + UUID.randomUUID();

    public DisputeDeadlineScheduler(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactionManager,
                                    ReturnResolutionService resolutions) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.resolutions = Objects.requireNonNull(resolutions, "退回解析服务不能为空");
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
            ClaimMetadata metadata = jdbc.query("SELECT dispute_case_id,deadline_type FROM dispute_deadline_claim WHERE id=?",
                rs -> rs.next() ? new ClaimMetadata(UUID.fromString(rs.getString(1)), rs.getString(2)) : null, handle.id().toString());
            Boolean result = transactions.execute(status -> processClaim(handle));
            if (Boolean.TRUE.equals(result) && metadata != null && "HARD_DEADLINE".equals(metadata.type())) {
                resolutions.executeHardDeadlineRefund(metadata.caseId());
            }
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
        var caseRow = jdbc.query("SELECT c.id,c.status,c.seller_deadline,c.admin_deadline,c.hard_deadline,c.proof_type,c.proof_reference,c.disputed_quantity,c.order_id,o.listing_id,o.unit_price_fen,p.id,p.paid_amount_fen,p.provider,p.status "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id LEFT JOIN payment_order p ON p.order_id=o.id AND p.status='SUCCEEDED' "
                + "WHERE c.id=? FOR UPDATE", rs -> rs.next() ? new CaseFacts(UUID.fromString(rs.getString(1)), rs.getString(2), ts(rs.getTimestamp(3)), ts(rs.getTimestamp(4)), ts(rs.getTimestamp(5)),
            rs.getString(6), rs.getString(7), rs.getInt(8), UUID.fromString(rs.getString(9)), UUID.fromString(rs.getString(10)), rs.getLong(11),
            rs.getString(12) == null ? null : UUID.fromString(rs.getString(12)), rs.getLong(13), rs.getString(14)) : null, claim.caseId().toString());
        if (caseRow == null) return complete(handle, claim);
        switch (claim.type()) {
            case "SELLER_RESPONSE" -> {
                if (!sellerDeadline(caseRow, now, handle, claim)) return false;
            }
            case "ADMIN_SLA" -> adminSla(caseRow, now, handle, claim);
            case "HARD_DEADLINE" -> hardDeadline(caseRow, now, handle, claim);
            default -> throw new IllegalArgumentException("未知争议截止类型");
        }
        return complete(handle, claim);
    }

    private boolean sellerDeadline(CaseFacts row, Instant now, ClaimHandle handle, Claim claim) {
        if (row.sellerDeadline() == null || now.isBefore(row.sellerDeadline())) {
            defer(handle, claim, row.sellerDeadline() == null ? plusDays(row.sellerDeadline(), 3, now) : row.sellerDeadline());
            return false;
        }
        if ("OPEN".equals(row.status())) {
            jdbc.update("UPDATE dispute_case SET status='UNDER_REVIEW',admin_deadline=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY),hard_deadline=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN'",
                row.caseId().toString());
            armAdminClaims(row.caseId());
        }
        return true;
    }

    /** 卖家超时是进入管理员阶段的事实点；缺失或被旧版本提前完成的 claim 都重新武装。 */
    private void armAdminClaims(UUID caseId) {
        jdbc.update("INSERT INTO dispute_deadline_claim (id,dispute_case_id,deadline_type,due_at,status,created_at,updated_at) "
                + "VALUES (UUID(),?, 'ADMIN_SLA',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY),'NEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)), "
                + "(UUID(),?, 'HARD_DEADLINE',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),'NEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE due_at=CASE deadline_type WHEN 'ADMIN_SLA' THEN DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY) WHEN 'HARD_DEADLINE' THEN DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY) ELSE due_at END,"
                + "status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,completed_at=NULL,updated_at=CURRENT_TIMESTAMP(6)", caseId.toString(), caseId.toString());
    }

    private void adminSla(CaseFacts row, Instant now, ClaimHandle handle, Claim claim) {
        if (terminal(row.status())) return;
        Instant deadline = row.adminDeadline();
        if (deadline == null || now.isBefore(deadline)) {
            defer(handle, claim, deadline == null ? plusDays(row.sellerDeadline(), 7, now) : deadline);
            return;
        }
        int changed = jdbc.update("UPDATE dispute_case SET admin_sla_alerted_at=COALESCE(admin_sla_alerted_at,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED') AND admin_sla_alerted_at IS NULL", row.caseId().toString());
        if (changed == 1) insertEvent("DISPUTE_SLA_ALERT", row.orderId(), row.caseId().toString(), "adminDeadline");
    }

    private void hardDeadline(CaseFacts row, Instant now, ClaimHandle handle, Claim claim) {
        if (terminal(row.status())) return;
        Instant deadline = row.hardDeadline();
        if (deadline == null || now.isBefore(deadline)) {
            defer(handle, claim, deadline == null ? plusDays(row.sellerDeadline(), 14, now) : deadline);
            return;
        }
        boolean trusted = row.proofType() != null && ReturnProofType.parse(row.proofType()).isTrusted();
        Long amount;
        try {
            amount = amount(row.unitPriceFen(), row.disputedQuantity());
        } catch (IllegalArgumentException overflow) {
            escalate(row.caseId());
            return;
        }
        if (!trusted || row.paymentId() == null || row.paidAmountFen() < amount) {
            escalate(row.caseId());
            return;
        }
        UUID returnCaseId = UUID.nameUUIDFromBytes((row.caseId() + "|RETURN_AND_REFUND").getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO return_case (id,dispute_case_id,order_id,listing_id,payment_order_id,unit_price_fen,status,proof_type,proof_reference,resolution_type,approved_quantity,deadline,created_at,updated_at) VALUES (?,?,?,?,?,?, 'REQUESTED',?,?, 'RETURN_AND_REFUND',?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE proof_type=VALUES(proof_type),proof_reference=VALUES(proof_reference),resolution_type=VALUES(resolution_type),approved_quantity=VALUES(approved_quantity),updated_at=CURRENT_TIMESTAMP(6)",
            returnCaseId.toString(), row.caseId().toString(), row.orderId().toString(), row.listingId().toString(), row.paymentId().toString(), row.unitPriceFen(), row.proofType(), row.proofReference(), row.disputedQuantity(), Timestamp.from(deadline));
        jdbc.update("UPDATE dispute_case SET status='RESOLVED',decision='RETURN_AND_REFUND',approved_quantity=?,resolved_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW')", row.disputedQuantity(), row.caseId().toString());
    }

    private void escalate(UUID caseId) {
        jdbc.update("UPDATE dispute_case SET status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW')", caseId.toString());
    }

    private void defer(ClaimHandle handle, Claim claim, Instant dueAt) {
        jdbc.update("UPDATE dispute_deadline_claim SET status='NEW',due_at=?,owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)",
            Timestamp.from(dueAt), handle.id().toString(), handle.owner(), handle.token());
    }

    private static Instant plusDays(Instant base, int days, Instant fallback) {
        return (base == null ? fallback : base).plus(Duration.ofDays(days));
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
    private static long amount(long unit, int quantity) {
        if (unit <= 0 || quantity <= 0) throw new IllegalArgumentException("退款金额参数无效");
        try { return Math.multiplyExact(unit, quantity); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("退款金额溢出", ex); }
    }
    private static Instant ts(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record Claim(UUID caseId, String type, Instant dueAt, String owner, String token, Instant leaseUntil) {}
    private record ClaimMetadata(UUID caseId, String type) {}
    private record ClaimHandle(UUID id, String owner, String token) {}
    private record CaseFacts(UUID caseId, String status, Instant sellerDeadline, Instant adminDeadline, Instant hardDeadline, String proofType,
                             String proofReference, int disputedQuantity, UUID orderId, UUID listingId, long unitPriceFen,
                             UUID paymentId, long paidAmountFen, String provider) {}
}
