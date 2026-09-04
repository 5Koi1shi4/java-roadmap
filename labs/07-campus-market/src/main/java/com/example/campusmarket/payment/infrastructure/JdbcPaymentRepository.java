package com.example.campusmarket.payment.infrastructure;

import com.example.campusmarket.payment.application.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.campusmarket.shared.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 支付数据访问；金额预占在 payment_order 聚合行上使用单条条件 UPDATE。 */
@Repository
@Profile("!test")
public class JdbcPaymentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPaymentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.mapper = Objects.requireNonNull(mapper, "JSON序列化器不能为空");
    }

    public UUID insertPendingPayment(UUID orderId, String provider, String idempotencyKey, Money amount) {
        return insertPendingPayment(orderId, provider, idempotencyKey, amount, null);
    }

    public UUID insertPendingPayment(UUID orderId, String provider, String idempotencyKey, Money amount, byte[] requestHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,request_hash,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=id
            """, id.toString(), orderId.toString(), provider, idempotencyKey, amount.fen(), requestHash);
        PaymentRecord row = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 FROM payment_order WHERE provider=? AND idempotency_key=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
            rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null, provider, idempotencyKey);
        if (row == null || !row.orderId().equals(orderId) || row.amountFen() != amount.fen()) {
            throw new IllegalArgumentException("支付幂等键与请求不一致");
        }
        return row.id();
    }

    public PaymentRecord findPayment(UUID paymentId) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 FROM payment_order WHERE id=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"),
                rs.getLong("paid_amount_fen"), rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            paymentId.toString());
    }

    public PaymentRecord findPaymentByReference(String provider, String reference) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 FROM payment_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"),
                rs.getLong("paid_amount_fen"), rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            provider, reference);
    }

    public boolean bindProviderPayment(UUID paymentId, String reference, PaymentGateway.PaymentStatus.Status status) {
        long paid = status == PaymentGateway.PaymentStatus.Status.SUCCEEDED ?
            jdbc.queryForObject("SELECT amount_fen FROM payment_order WHERE id=?", Long.class, paymentId.toString()) : 0L;
        return jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=?,status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','CREATED')",
            reference, paid, status.name(), paymentId.toString()) == 1;
    }

    public boolean markPaymentSucceededByReference(String provider, String reference, long amountFen) {
        return jdbc.update("UPDATE payment_order SET paid_amount_fen=amount_fen,status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP(6) WHERE provider=? AND provider_reference=? AND status IN ('PENDING','CREATED') AND amount_fen=?",
            provider, reference, amountFen) == 1;
    }

    public void savePaymentResponse(UUID paymentId, byte[] responseUtf8) {
        jdbc.update("UPDATE payment_order SET response_utf8=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", responseUtf8, paymentId.toString());
    }

    /** 为首次 provider IO 建立短时租约，应用并发请求只有一个 owner。 */
    public boolean claimPaymentRequest(UUID paymentId) {
        return jdbc.update("UPDATE payment_order SET next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider_reference IS NULL AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6))", paymentId.toString()) == 1;
    }

    public boolean claimPaymentReconciliation(UUID paymentId) {
        return claimPaymentReconciliation(paymentId, "payment-reconciler", UUID.randomUUID().toString());
    }

    public boolean claimPaymentReconciliationDirect(UUID paymentId, String owner, String token) {
        return jdbc.update("UPDATE payment_order SET reconcile_owner=?,reconcile_token=?,reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','UNKNOWN') AND (reconcile_owner IS NULL OR reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6))", owner, token, paymentId.toString()) == 1;
    }

    public boolean claimPaymentReconciliation(UUID paymentId, String owner, String token) {
        return jdbc.update("UPDATE payment_order SET reconcile_owner=?,reconcile_token=?,reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','UNKNOWN') AND ((reconcile_owner IS NULL AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6))) OR (reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6)))", owner, token, paymentId.toString()) == 1;
    }

    public java.util.List<UUID> duePaymentReconciliations(int limit) {
        return jdbc.query("SELECT id FROM payment_order WHERE status IN ('PENDING','UNKNOWN') AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6)) AND (reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY COALESCE(next_reconcile_at,created_at),created_at LIMIT ?",
            (rs, rowNum) -> UUID.fromString(rs.getString(1)), limit);
    }

    public java.util.List<UUID> dueRefundReconciliations(int limit) {
        return jdbc.query("SELECT id FROM refund_order WHERE status IN ('PROCESSING','UNKNOWN','REQUESTED') AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6)) AND (reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY COALESCE(next_reconcile_at,created_at),created_at LIMIT ?",
            (rs, rowNum) -> UUID.fromString(rs.getString(1)), limit);
    }

    public boolean claimRefundReconciliation(UUID refundId) {
        return claimRefundReconciliation(refundId, "refund-reconciler", UUID.randomUUID().toString());
    }

    public boolean claimRefundReconciliationDirect(UUID refundId, String owner, String token) {
        return jdbc.update("UPDATE refund_order SET reconcile_owner=?,reconcile_token=?,reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PROCESSING','UNKNOWN','REQUESTED') AND (reconcile_owner IS NULL OR reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6))", owner, token, refundId.toString()) == 1;
    }

    public boolean markRefundTerminal(UUID refundId, String expectedReference, String reference, String status,
                                      long amountFen, String owner, String token) {
        String predicate = expectedReference == null ? "provider_reference IS NULL AND ? IS NOT NULL" : "provider_reference=?";
        Object[] args = expectedReference == null
            ? new Object[]{status, reference, refundId.toString(), reference, amountFen, owner, token}
            : new Object[]{status, reference, refundId.toString(), expectedReference, amountFen, owner, token};
        return jdbc.update("UPDATE refund_order SET status=?,provider_reference=COALESCE(?,provider_reference),reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND " + predicate + " AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN') AND reconcile_owner=? AND reconcile_token=?", args) == 1;
    }

    public boolean claimRefundReconciliation(UUID refundId, String owner, String token) {
        return jdbc.update("UPDATE refund_order SET reconcile_owner=?,reconcile_token=?,reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PROCESSING','UNKNOWN','REQUESTED') AND ((reconcile_owner IS NULL AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6))) OR (reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6)))", owner, token, refundId.toString()) == 1;
    }

    public boolean markPaymentUnknown(UUID paymentId) {
        return jdbc.update("UPDATE payment_order SET status='UNKNOWN',next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','CREATED','UNKNOWN')", paymentId.toString()) == 1;
    }

    public boolean bindUnknownPaymentReference(UUID paymentId, String reference) {
        return jdbc.update("UPDATE payment_order SET provider_reference=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider_reference IS NULL AND status='UNKNOWN'", reference, paymentId.toString()) == 1;
    }

    public boolean markPaymentSucceeded(UUID paymentId, String reference, long amountFen) {
        return jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=amount_fen,status='SUCCEEDED',reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND amount_fen=? AND status IN ('PENDING','UNKNOWN') AND (provider_reference=? OR provider_reference IS NULL)", reference, paymentId.toString(), amountFen, reference) == 1;
    }

    public boolean markPaymentSucceeded(UUID paymentId, String expectedReference, String reference, long amountFen,
                                        String owner, String token) {
        String predicate = expectedReference == null ? "provider_reference IS NULL" : "provider_reference=?";
        Object[] args = expectedReference == null
            ? new Object[]{reference, paymentId.toString(), amountFen, owner, token}
            : new Object[]{reference, paymentId.toString(), expectedReference, amountFen, owner, token};
        return jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=amount_fen,status='SUCCEEDED',reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND " + predicate + " AND amount_fen=? AND status IN ('PENDING','UNKNOWN') AND reconcile_owner=? AND reconcile_token=?", args) == 1;
    }

    public boolean markPaymentFailed(UUID paymentId, String expectedReference, String owner, String token) {
        return jdbc.update("UPDATE payment_order SET status='FAILED',reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider_reference=? AND status IN ('PENDING','UNKNOWN') AND reconcile_owner=? AND reconcile_token=?", paymentId.toString(), expectedReference, owner, token) == 1;
    }

    /** 在同一支付聚合行上串行化并校验 successful + reserved + requested <= paid。 */
    public boolean reserveRefund(UUID paymentId, long amountFen) {
        if (amountFen <= 0) throw new IllegalArgumentException("退款金额必须为正数");
        return jdbc.update("UPDATE payment_order SET reserved_refund_fen=reserved_refund_fen+?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='SUCCEEDED' AND successful_refund_fen+reserved_refund_fen+?<=paid_amount_fen",
            amountFen, paymentId.toString(), amountFen) == 1;
    }

    public boolean completeRefund(UUID paymentId, long amountFen) {
        return jdbc.update("UPDATE payment_order SET reserved_refund_fen=reserved_refund_fen-?,successful_refund_fen=successful_refund_fen+?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND reserved_refund_fen>=?",
            amountFen, amountFen, paymentId.toString(), amountFen) == 1;
    }

    public boolean releaseRefund(UUID paymentId, long amountFen) {
        return jdbc.update("UPDATE payment_order SET reserved_refund_fen=reserved_refund_fen-?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND reserved_refund_fen>=?",
            amountFen, paymentId.toString(), amountFen) == 1;
    }

    public RefundInsert insertRefund(UUID orderId, UUID paymentId, String provider, String idempotencyKey,
                             String sourceType, UUID sourceId, long paidAmountFen, long amountFen) {
        return insertRefund(orderId, paymentId, provider, idempotencyKey, sourceType, sourceId, paidAmountFen, amountFen, null);
    }

    public RefundInsert insertRefund(UUID orderId, UUID paymentId, String provider, String idempotencyKey,
                             String sourceType, UUID sourceId, long paidAmountFen, long amountFen, byte[] requestHash) {
        UUID id = UUID.nameUUIDFromBytes((orderId + "|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        int changed = jdbc.update("""
            INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,
                paid_amount_fen,amount_fen,reserved_refund_fen,request_hash,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=id
            """, id.toString(), orderId.toString(), paymentId.toString(), provider, idempotencyKey,
            sourceType, sourceId == null ? null : sourceId.toString(), paidAmountFen, amountFen, amountFen, requestHash);
        return new RefundInsert(id, changed == 1);
    }

    public boolean bindRefundProvider(UUID refundId, String reference, PaymentGateway.RefundStatus.Status status) {
        return jdbc.update("UPDATE refund_order SET provider_reference=?,status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING')",
            reference, status.name(), refundId.toString()) == 1;
    }

    public RefundRecord findRefund(UUID refundId) {
        return jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status,request_hash,response_utf8 FROM refund_order WHERE id=?",
            rs -> rs.next() ? new RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"),
                rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            refundId.toString());
    }

    public RefundRecord findRefundByKey(UUID orderId, String idempotencyKey) {
        return jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status,request_hash,response_utf8 FROM refund_order WHERE order_id=? AND idempotency_key=? FOR UPDATE",
            rs -> rs.next() ? new RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")), UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            orderId.toString(), idempotencyKey);
    }

    public void saveRefundRequest(UUID refundId, byte[] requestHash) {
        jdbc.update("UPDATE refund_order SET request_hash=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", requestHash, refundId.toString());
    }

    public void saveRefundResponse(UUID refundId, byte[] responseUtf8) {
        jdbc.update("UPDATE refund_order SET response_utf8=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", responseUtf8, refundId.toString());
    }

    public boolean claimRefundRequest(UUID refundId) {
        return jdbc.update("UPDATE refund_order SET next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider_reference IS NULL AND status='REQUESTED' AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6))", refundId.toString()) == 1;
    }

    public boolean recordCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        try {
            return jdbc.update("""
                INSERT INTO payment_callback_event (id,provider,provider_event_id,payload,payload_digest,signature_valid,status,received_at)
                VALUES (?,?,?,CAST(? AS JSON),? ,TRUE,'RECEIVED',CURRENT_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), callback.provider(), callback.providerEventId(),
                sanitizedPayload(callback), digest(rawBody)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public boolean completeCallback(String provider, String eventId) {
        return jdbc.update("UPDATE payment_callback_event SET status='COMPLETED',processed_at=CURRENT_TIMESTAMP(6) WHERE provider=? AND provider_event_id=? AND status IN ('RECEIVED','PROCESSING')",
            provider, eventId) == 1;
    }

    public boolean failCallback(String provider, String eventId) {
        return jdbc.update("UPDATE payment_callback_event SET status='FAILED',processed_at=CURRENT_TIMESTAMP(6) WHERE provider=? AND provider_event_id=? AND status IN ('RECEIVED','PROCESSING')", provider, eventId) == 1;
    }

    public void insertPaymentEvent(String eventType, UUID aggregateId, String payload) {
        jdbc.update("""
            INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                occurred_at,payload,status,attempt_count,available_at,created_at)
            VALUES (?,?,?, ?,1,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType, aggregateId.toString(), payload);
    }

    public record PaymentRecord(UUID id, UUID orderId, String provider, String idempotencyKey,
                                long amountFen, long paidAmountFen, String providerReference, String status,
                                byte[] requestHash, byte[] responseUtf8) {
        public PaymentRecord(UUID id, UUID orderId, String provider, String idempotencyKey,
                             long amountFen, long paidAmountFen, String providerReference, String status,
                             byte[] requestHash) {
            this(id, orderId, provider, idempotencyKey, amountFen, paidAmountFen, providerReference, status, requestHash, null);
        }
        public PaymentRecord(UUID id, UUID orderId, String provider, String idempotencyKey,
                             long amountFen, long paidAmountFen, String providerReference, String status) {
            this(id, orderId, provider, idempotencyKey, amountFen, paidAmountFen, providerReference, status, null, null);
        }
    }
    public record RefundRecord(UUID id, UUID orderId, UUID paymentId, String provider, String idempotencyKey,
                               long amountFen, String providerReference, String status, byte[] requestHash, byte[] responseUtf8) {
        public RefundRecord(UUID id, UUID orderId, UUID paymentId, String provider, String idempotencyKey,
                            long amountFen, String providerReference, String status) {
            this(id, orderId, paymentId, provider, idempotencyKey, amountFen, providerReference, status, null, null);
        }
    }
    public record RefundInsert(UUID id, boolean inserted) {}

    private static byte[] digest(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
    private String sanitizedPayload(PaymentGateway.VerifiedCallback callback) {
        try {
            return mapper.writeValueAsString(java.util.Map.of("provider", callback.provider(), "providerEventId", callback.providerEventId(),
                "type", callback.type().name(), "providerReference", callback.providerReference(), "amountFen", callback.amountFen(),
                "status", callback.status(), "occurredAt", callback.occurredAt().toString()));
        } catch (Exception e) { throw new IllegalStateException("回调摘要序列化失败", e); }
    }
}
