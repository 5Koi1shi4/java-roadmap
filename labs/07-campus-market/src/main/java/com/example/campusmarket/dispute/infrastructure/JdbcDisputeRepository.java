package com.example.campusmarket.dispute.infrastructure;

import com.example.campusmarket.dispute.domain.DisputeCase;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.DisputeReason;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 普通争议的 MySQL 持久化；调用者负责事务边界。 */
@Repository
public class JdbcDisputeRepository {
    private final JdbcTemplate jdbc;
    private final JdbcOrderLifecycleRepository orders;

    public JdbcDisputeRepository(JdbcTemplate jdbc, JdbcOrderLifecycleRepository orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    public JdbcOrderLifecycleRepository.OrderRow lockOrder(UUID orderId) { return orders.lock(orderId); }
    public Instant databaseNow() { return orders.databaseNow(); }

    public int cumulativeReservedQuantity(UUID orderId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED') THEN disputed_quantity WHEN status='RESOLVED' THEN COALESCE(approved_quantity,0) ELSE 0 END),0) FROM dispute_case WHERE order_id=?", Integer.class, orderId.toString());
        return value == null ? 0 : value;
    }

    public void insert(DisputeCase file, Instant now) {
        Instant sellerDeadline = now.plus(Duration.ofHours(72));
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,admin_deadline,hard_deadline,decision,approved_quantity,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,'OPEN',?,NULL,NULL,NULL,NULL,0,?,?,?)",
            file.id().toString(), file.orderId().toString(), file.buyerId().toString(), file.disputedQuantity(), file.reason().name(),
            Timestamp.from(sellerDeadline), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO dispute_deadline_claim (id,dispute_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?, 'SELLER_RESPONSE',?,'NEW',?,?)",
            UUID.randomUUID().toString(), file.id().toString(), Timestamp.from(sellerDeadline), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO dispute_deadline_claim (id,dispute_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?, 'ADMIN_SLA',?,'NEW',?,?)",
            UUID.randomUUID().toString(), file.id().toString(), Timestamp.from(sellerDeadline), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO dispute_deadline_claim (id,dispute_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?, 'HARD_DEADLINE',?,'NEW',?,?)",
            UUID.randomUUID().toString(), file.id().toString(), Timestamp.from(sellerDeadline), Timestamp.from(now), Timestamp.from(now));
    }

    public int markDisputed(UUID orderId, UUID actorId, long version, Instant now) {
        return orders.transition(orderId, OrderStatus.AWAITING_RECEIPT, OrderStatus.DISPUTED, version, now,
            null, false, null, null, null, "DISPUTE_OPENED", actorId)
            + orders.transition(orderId, OrderStatus.AFTERSALE_WINDOW, OrderStatus.DISPUTED, version, now,
            null, false, null, null, null, "DISPUTE_OPENED", actorId);
    }

    public CaseRow find(UUID caseId) {
        return jdbc.query("SELECT id,order_id,initiator_id,assigned_admin_id,disputed_quantity,reason,status,decision,approved_quantity,version,seller_deadline,seller_response,admin_deadline,opened_at FROM dispute_case WHERE id=?",
            rs -> { if (!rs.next()) return null; return new CaseRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("initiator_id")), uuid(rs.getString("assigned_admin_id")), rs.getInt("disputed_quantity"),
                DisputeReason.parse(rs.getString("reason")), DisputeCase.Status.valueOf(rs.getString("status")),
                rs.getString("decision") == null ? null : DisputeDecision.valueOf(rs.getString("decision")),
                (Integer) rs.getObject("approved_quantity"), rs.getLong("version"), instant(rs.getTimestamp("seller_deadline")),
                rs.getString("seller_response"), instant(rs.getTimestamp("admin_deadline")), instant(rs.getTimestamp("opened_at"))); }, caseId.toString());
    }

    public CaseRow lock(UUID caseId) {
        return jdbc.query("SELECT id,order_id,initiator_id,assigned_admin_id,disputed_quantity,reason,status,decision,approved_quantity,version,seller_deadline,seller_response,admin_deadline,opened_at FROM dispute_case WHERE id=? FOR UPDATE",
            rs -> { if (!rs.next()) return null; return new CaseRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("initiator_id")), uuid(rs.getString("assigned_admin_id")), rs.getInt("disputed_quantity"),
                DisputeReason.parse(rs.getString("reason")), DisputeCase.Status.valueOf(rs.getString("status")),
                rs.getString("decision") == null ? null : DisputeDecision.valueOf(rs.getString("decision")),
                (Integer) rs.getObject("approved_quantity"), rs.getLong("version"), instant(rs.getTimestamp("seller_deadline")),
                rs.getString("seller_response"), instant(rs.getTimestamp("admin_deadline")), instant(rs.getTimestamp("opened_at"))); }, caseId.toString());
    }

    public int markSellerResponded(UUID caseId, long version, Instant now, String response) {
        return jdbc.update("UPDATE dispute_case SET status='SELLER_RESPONDED',seller_response=?,version=version+1,updated_at=? WHERE id=? AND status='OPEN' AND version=?",
            response, Timestamp.from(now), caseId.toString(), version);
    }

    public int assignAdmin(UUID caseId, UUID adminId, long version, Instant now) {
        int changed = jdbc.update("UPDATE dispute_case SET assigned_admin_id=?,status=CASE WHEN status='OPEN' THEN 'UNDER_REVIEW' ELSE status END,admin_deadline=?,hard_deadline=?,version=version+1,updated_at=? WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW') AND version=?",
            adminId.toString(), Timestamp.from(now.plus(Duration.ofDays(7))), Timestamp.from(now.plus(Duration.ofDays(14))), Timestamp.from(now), caseId.toString(), version);
        if (changed == 1) {
            jdbc.update("UPDATE dispute_deadline_claim SET due_at=CASE deadline_type WHEN 'ADMIN_SLA' THEN ? WHEN 'HARD_DEADLINE' THEN ? ELSE due_at END,updated_at=? WHERE dispute_case_id=? AND deadline_type IN ('ADMIN_SLA','HARD_DEADLINE') AND status='NEW'",
                Timestamp.from(now.plus(Duration.ofDays(7))), Timestamp.from(now.plus(Duration.ofDays(14))), Timestamp.from(now), caseId.toString());
        }
        return changed;
    }

    public int decide(UUID caseId, long version, DisputeDecision decision, int approvedQuantity, Instant now) {
        return jdbc.update("UPDATE dispute_case SET status=?,decision=?,approved_quantity=?,resolved_at=?,version=version+1,updated_at=? WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED') AND version=?",
            decision == DisputeDecision.REJECT ? "REJECTED" : "RESOLVED", decision.name(), approvedQuantity == 0 ? null : approvedQuantity,
            Timestamp.from(now), Timestamp.from(now), caseId.toString(), version);
    }

    public int releaseOrderAfterRejection(UUID orderId, UUID actorId, long version, Instant now) {
        return orders.transition(orderId, OrderStatus.DISPUTED, OrderStatus.AFTERSALE_WINDOW, version, now,
            null, false, null, null, null, "DISPUTE_REJECTED", actorId);
    }

    public EvidenceRow evidence(UUID evidenceId) {
        return jdbc.query("SELECT e.id,e.dispute_case_id,e.object_key,e.media_type,e.size_bytes,c.order_id,o.buyer_id,o.seller_id,c.assigned_admin_id FROM dispute_evidence e JOIN dispute_case c ON c.id=e.dispute_case_id JOIN trade_order o ON o.id=c.order_id WHERE e.id=?",
            rs -> rs.next() ? new EvidenceRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("dispute_case_id")),
                rs.getString("object_key"), rs.getString("media_type"), rs.getLong("size_bytes"), UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("buyer_id")), UUID.fromString(rs.getString("seller_id")), uuid(rs.getString("assigned_admin_id"))) : null,
            evidenceId.toString());
    }

    public CaseAccess access(UUID caseId) {
        return jdbc.query("SELECT c.id,o.buyer_id,o.seller_id,c.assigned_admin_id FROM dispute_case c JOIN trade_order o ON o.id=c.order_id WHERE c.id=?",
            rs -> rs.next() ? new CaseAccess(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("buyer_id")), UUID.fromString(rs.getString("seller_id")), uuid(rs.getString("assigned_admin_id"))) : null,
            caseId.toString());
    }

    public void insertEvidence(UUID evidenceId, UUID caseId, UUID actorId, String key, String type, long size, Instant now) {
        jdbc.update("INSERT INTO dispute_evidence (id,dispute_case_id,warranty_case_id,case_type,submitted_by,object_key,media_type,size_bytes,created_at) VALUES (?,? ,NULL,'DISPUTE',?,?,?, ?,?)",
            evidenceId.toString(), caseId.toString(), actorId.toString(), key, type, size, Timestamp.from(now));
    }

    public void createSession(UUID sessionId, UUID actorId, String key, String claimToken) {
        jdbc.update("INSERT INTO object_upload_session (id,submitted_by,purpose,object_key,status,claim_token,owner_id,expires_at,created_at,updated_at) VALUES (?,?,'DISPUTE_EVIDENCE',?,'OPEN',?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", sessionId.toString(), actorId.toString(), key, claimToken, actorId.toString());
    }
    public int completeSession(UUID id, UUID ownerId, String claimToken) { return jdbc.update("UPDATE object_upload_session SET status='COMPLETED',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN' AND owner_id=? AND claim_token=? AND expires_at > CURRENT_TIMESTAMP(6)", id.toString(), ownerId.toString(), claimToken); }
    public int abortSession(UUID id, UUID ownerId, String claimToken) { return jdbc.update("UPDATE object_upload_session SET status='ABORTED',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN' AND owner_id=? AND claim_token=?", id.toString(), ownerId.toString(), claimToken); }
    public void cleanup(UUID sessionId, String key) { jdbc.update("INSERT INTO storage_cleanup_task (id,cleanup_business_key,object_key,status,run_after,created_at,updated_at) VALUES (?,?,?,'PENDING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP(6)", UUID.randomUUID().toString(), "dispute-upload:" + sessionId, key); }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record CaseRow(UUID id, UUID orderId, UUID initiatorId, UUID assignedAdminId, int disputedQuantity,
                          DisputeReason reason, DisputeCase.Status status, DisputeDecision decision, Integer approvedQuantity,
                          long version, Instant sellerDeadline, String sellerResponse, Instant adminDeadline, Instant openedAt) {}
    public record CaseAccess(UUID caseId, UUID buyerId, UUID sellerId, UUID assignedAdminId) {}
    public record EvidenceRow(UUID id, UUID caseId, String objectKey, String mediaType, long sizeBytes, UUID orderId,
                              UUID buyerId, UUID sellerId, UUID assignedAdminId) {}
}
