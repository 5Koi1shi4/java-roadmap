package com.example.campusmarket.payment.infrastructure;

import com.example.campusmarket.payment.application.PaymentGateway;
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

    public JdbcPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
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
        PaymentRecord row = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash FROM payment_order WHERE provider=? AND idempotency_key=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
            rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash")) : null, provider, idempotencyKey);
        if (row == null || !row.orderId().equals(orderId) || row.amountFen() != amount.fen()) {
            throw new IllegalArgumentException("支付幂等键与请求不一致");
        }
        return row.id();
    }

    public PaymentRecord findPayment(UUID paymentId) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash FROM payment_order WHERE id=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"),
                rs.getLong("paid_amount_fen"), rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash")) : null,
            paymentId.toString());
    }

    public PaymentRecord findPaymentByReference(String provider, String reference) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash FROM payment_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"),
                rs.getLong("paid_amount_fen"), rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash")) : null,
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
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,
                paid_amount_fen,amount_fen,reserved_refund_fen,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,? ,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=id
            """, id.toString(), orderId.toString(), paymentId.toString(), provider, idempotencyKey,
            sourceType, sourceId == null ? null : sourceId.toString(), paidAmountFen, amountFen, amountFen);
        UUID actual = jdbc.queryForObject("SELECT id FROM refund_order WHERE order_id=? AND idempotency_key=?",
            (rs, rowNum) -> UUID.fromString(rs.getString(1)), orderId.toString(), idempotencyKey);
        return new RefundInsert(actual, actual.equals(id));
    }

    public boolean bindRefundProvider(UUID refundId, String reference, PaymentGateway.RefundStatus.Status status) {
        return jdbc.update("UPDATE refund_order SET provider_reference=?,status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING')",
            reference, status.name(), refundId.toString()) == 1;
    }

    public RefundRecord findRefund(UUID refundId) {
        return jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status FROM refund_order WHERE id=?",
            rs -> rs.next() ? new RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"),
                rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status")) : null,
            refundId.toString());
    }

    public RefundRecord findRefundByKey(UUID orderId, String idempotencyKey) {
        return jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status FROM refund_order WHERE order_id=? AND idempotency_key=?",
            rs -> rs.next() ? new RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")), UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status")) : null,
            orderId.toString(), idempotencyKey);
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

    public void insertPaymentEvent(String eventType, UUID aggregateId, String payload) {
        jdbc.update("""
            INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                occurred_at,payload,status,attempt_count,available_at,created_at)
            VALUES (?,?,?, ?,1,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType, aggregateId.toString(), payload);
    }

    public record PaymentRecord(UUID id, UUID orderId, String provider, String idempotencyKey,
                                long amountFen, long paidAmountFen, String providerReference, String status,
                                byte[] requestHash) {
        public PaymentRecord(UUID id, UUID orderId, String provider, String idempotencyKey,
                             long amountFen, long paidAmountFen, String providerReference, String status) {
            this(id, orderId, provider, idempotencyKey, amountFen, paidAmountFen, providerReference, status, null);
        }
    }
    public record RefundRecord(UUID id, UUID orderId, UUID paymentId, String provider, String idempotencyKey,
                               long amountFen, String providerReference, String status) {}
    public record RefundInsert(UUID id, boolean inserted) {}

    private static byte[] digest(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
    private static String sanitizedPayload(PaymentGateway.VerifiedCallback callback) {
        return "{\"provider\":\"" + callback.provider() + "\",\"providerEventId\":\"" + callback.providerEventId()
            + "\",\"type\":\"" + callback.type() + "\",\"providerReference\":\"" + callback.providerReference()
            + "\",\"amountFen\":" + callback.amountFen() + ",\"status\":\"" + callback.status()
            + "\",\"occurredAt\":\"" + callback.occurredAt() + "\"}";
    }
}
