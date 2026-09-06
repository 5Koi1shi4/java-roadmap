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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public PaymentRecord findPaymentByOrderAndKey(UUID orderId, String provider, String idempotencyKey) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 FROM payment_order WHERE order_id=? AND provider=? AND idempotency_key=?",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            orderId.toString(), provider, idempotencyKey);
    }

    /** 查询订单支付用的金额和当前状态；支付服务不直接依赖订单表 SQL。 */
    public OrderRecord findOrderForPayment(UUID orderId) {
        return jdbc.query("SELECT id,total_amount_fen,status FROM trade_order WHERE id=?",
            rs -> rs.next() ? new OrderRecord(UUID.fromString(rs.getString("id")), rs.getLong("total_amount_fen"), rs.getString("status")) : null,
            orderId.toString());
    }

    /** 查询订单最近一笔成功支付；退款服务只通过该业务查询获取可退款支付。 */
    public PaymentRecord findLatestSuccessfulPaymentByOrder(UUID orderId) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 " +
                "FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            orderId.toString());
    }

    /** 退款与结算统一先锁订单行，避免资格检查后被并发退款越过结算门禁。 */
    public OrderRecord lockOrderForRefund(UUID orderId) {
        return jdbc.query("SELECT id,total_amount_fen,status FROM trade_order WHERE id=? FOR UPDATE",
            rs -> rs.next() ? new OrderRecord(UUID.fromString(rs.getString("id")), rs.getLong("total_amount_fen"), rs.getString("status")) : null,
            orderId.toString());
    }

    /** 与订单锁配套的支付行锁，固定订单→支付的锁顺序。 */
    public PaymentRecord findLatestSuccessfulPaymentByOrderForUpdate(UUID orderId) {
        return jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 "
                + "FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            orderId.toString());
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

    /** 仅允许仍持有首次请求租约的 owner 绑定 provider 结果；过期 owner 即使迟到也不得落库。 */
    public boolean bindProviderPayment(UUID paymentId, String reference, PaymentGateway.PaymentStatus.Status status,
                                       String owner, String token) {
        long paid = status == PaymentGateway.PaymentStatus.Status.SUCCEEDED
            ? jdbc.queryForObject("SELECT amount_fen FROM payment_order WHERE id=?", Long.class, paymentId.toString()) : 0L;
        return jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=?,status=?,updated_at=CURRENT_TIMESTAMP(6) " +
                "WHERE id=? AND provider_reference IS NULL AND status IN ('PENDING','CREATED','UNKNOWN') " +
                "AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)",
            reference, paid, status.name(), paymentId.toString(), owner, token) == 1;
    }

    public boolean markPaymentSucceededByReference(String provider, String reference, long amountFen) {
        return jdbc.update("UPDATE payment_order SET paid_amount_fen=amount_fen,status='SUCCEEDED',reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE provider=? AND provider_reference=? AND status IN ('PENDING','CREATED','UNKNOWN') AND amount_fen=?",
            provider, reference, amountFen) == 1;
    }

    /** 带订单号的已验签回调必须在同一条件更新中绑定 reference 的订单归属。 */
    public boolean markPaymentSucceededByReference(UUID orderId, String provider, String reference, long amountFen) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        return jdbc.update("UPDATE payment_order SET paid_amount_fen=amount_fen,status='SUCCEEDED',reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE order_id=? AND provider=? AND provider_reference=? AND status IN ('PENDING','CREATED','UNKNOWN') AND amount_fen=?",
            orderId.toString(), provider, reference, amountFen) == 1;
    }

    public void savePaymentResponse(UUID paymentId, byte[] responseUtf8) {
        jdbc.update("UPDATE payment_order SET response_utf8=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", responseUtf8, paymentId.toString());
    }

    /** 绑定 owner/token 并释放租约；终态已被新 owner 接管时条件更新为 0。 */
    public boolean savePaymentResponseAndRelease(UUID paymentId, byte[] responseUtf8, String owner, String token) {
        return jdbc.update("UPDATE payment_order SET response_utf8=?,reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL," +
                "next_reconcile_at=CASE WHEN status='UNKNOWN' THEN DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) ELSE NULL END," +
                "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)",
            responseUtf8, paymentId.toString(), owner, token) == 1;
    }

    /** 支付成功时将订单从待支付 CAS 推进为待交付；调用方事务负责回滚终态。 */
    public int advanceOrderAfterPayment(UUID paymentId, UUID orderId) {
        Long expectedVersion = jdbc.query("SELECT version FROM trade_order WHERE id=? FOR UPDATE",
            rs -> rs.next() ? rs.getLong(1) : null, orderId.toString());
        if (expectedVersion == null) return 0;
        int changed = jdbc.update("""
            UPDATE trade_order o JOIN payment_order p ON p.order_id=o.id SET o.paid_amount_fen=p.paid_amount_fen,
                o.status='AWAITING_HANDOFF',o.handoff_deadline=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 72 HOUR),o.version=o.version+1,
                o.updated_at=CURRENT_TIMESTAMP(6) WHERE p.id=? AND o.id=? AND o.status='PENDING_PAYMENT' AND o.version=?
            """,
            paymentId.toString(), orderId.toString(), expectedVersion);
        if (changed == 1) {
            Long version = jdbc.queryForObject("SELECT version FROM trade_order WHERE id=?", Long.class, orderId.toString());
            jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) SELECT ?,?, 'HANDOFF','NEW',handoff_deadline FROM trade_order WHERE id=? ON DUPLICATE KEY UPDATE due_at=VALUES(due_at)",
                UUID.randomUUID().toString(), orderId.toString(), orderId.toString());
            jdbc.update("INSERT INTO order_transition (id,order_id,from_status,to_status,reason,occurred_at) VALUES (?,?, 'PENDING_PAYMENT','AWAITING_HANDOFF','PAYMENT_SUCCEEDED',CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), orderId.toString());
            String payload = "{\"orderId\":\"" + orderId + "\",\"status\":\"AWAITING_HANDOFF\",\"paymentId\":\"" + paymentId + "\"}";
            jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?, 'ORDER_PAID',?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), orderId.toString(), version, payload);
        }
        return changed;
    }

    /** 为首次 provider IO 建立短时租约，应用并发请求只有一个 owner。 */
    public boolean claimPaymentRequest(UUID paymentId) {
        return claimPaymentRequest(paymentId, "payment-request", UUID.randomUUID().toString());
    }

    /** 首次 provider create 只能成功一次；租约到期或 UNKNOWN 不会重新取得 create 权。 */
    public boolean claimPaymentRequest(UUID paymentId, String owner, String token) {
        return claimInitialPaymentAttempt(paymentId, owner, token);
    }

    public boolean claimInitialPaymentAttempt(UUID paymentId, String owner, String token) {
        return jdbc.update("""
            UPDATE payment_order
               SET create_attempted_at=CURRENT_TIMESTAMP(6),
                   reconcile_owner=?, reconcile_token=?,
                   reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),
                   next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),
                   updated_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND create_attempted_at IS NULL
               AND provider_reference IS NULL AND status IN ('PENDING','CREATED')
            """, owner, token, paymentId.toString()) == 1;
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
        return jdbc.update("UPDATE refund_order SET status=?,provider_reference=COALESCE(?,provider_reference),reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND " + predicate + " AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN') AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)", args) == 1;
    }

    public boolean claimRefundReconciliation(UUID refundId, String owner, String token) {
        return jdbc.update("UPDATE refund_order SET reconcile_owner=?,reconcile_token=?,reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PROCESSING','UNKNOWN','REQUESTED') AND ((reconcile_owner IS NULL AND (next_reconcile_at IS NULL OR next_reconcile_at<=CURRENT_TIMESTAMP(6))) OR (reconcile_lease_until IS NULL OR reconcile_lease_until<=CURRENT_TIMESTAMP(6)))", owner, token, refundId.toString()) == 1;
    }

    public boolean markPaymentUnknown(UUID paymentId) {
        return jdbc.update("UPDATE payment_order SET status='UNKNOWN',next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','CREATED','UNKNOWN')", paymentId.toString()) == 1;
    }

    public boolean markPaymentUnknown(UUID paymentId, String owner, String token) {
        return jdbc.update("UPDATE payment_order SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','CREATED','UNKNOWN') " +
                "AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)", paymentId.toString(), owner, token) == 1;
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
        return jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=amount_fen,status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND " + predicate + " AND amount_fen=? AND status IN ('PENDING','UNKNOWN') AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)", args) == 1;
    }

    public boolean markPaymentFailed(UUID paymentId, String expectedReference, String owner, String token) {
        return markPaymentFailed(paymentId, expectedReference, expectedReference, -1L, owner, token);
    }

    public boolean markPaymentFailed(UUID paymentId, String expectedReference, String reference, long amountFen,
                                     String owner, String token) {
        String predicate = expectedReference == null ? "provider_reference IS NULL" : "provider_reference=?";
        String amountPredicate = amountFen < 0 ? "" : " AND amount_fen=?";
        Object[] args;
        if (expectedReference == null) {
            args = amountFen < 0 ? new Object[]{reference, paymentId.toString(), owner, token}
                : new Object[]{reference, paymentId.toString(), amountFen, owner, token};
        } else {
            args = amountFen < 0 ? new Object[]{reference, paymentId.toString(), expectedReference, owner, token}
                : new Object[]{reference, paymentId.toString(), expectedReference, amountFen, owner, token};
        }
        return jdbc.update("UPDATE payment_order SET provider_reference=COALESCE(?,provider_reference),status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND " + predicate + amountPredicate + " AND status IN ('PENDING','UNKNOWN') AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)", args) == 1;
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

    /** Provider create 返回待定结果时，仅由当前 fencing owner 绑定 reference 并释放租约。 */
    public boolean markRefundProcessing(UUID refundId, String expectedReference, String reference,
                                        long amountFen, String owner, String token) {
        String predicate = expectedReference == null ? "provider_reference IS NULL AND ? IS NOT NULL" : "provider_reference=?";
        Object[] args = expectedReference == null
            ? new Object[]{reference, refundId.toString(), reference, amountFen, owner, token}
            : new Object[]{reference, refundId.toString(), expectedReference, amountFen, owner, token};
        return jdbc.update("UPDATE refund_order SET status='PROCESSING',provider_reference=COALESCE(?,provider_reference)," +
            "reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND)," +
            "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND " + predicate + " AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')" +
            " AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)", args) == 1;
    }

    /** Provider 结果未知时保留不可逆 create 事实，仅当前 fencing owner 可释放租约。 */
    public boolean markRefundUnknown(UUID refundId, String reference, String owner, String token) {
        return jdbc.update("UPDATE refund_order SET status='UNKNOWN',provider_reference=COALESCE(?,provider_reference)," +
            "reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND)," +
            "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')" +
            " AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)",
            reference, refundId.toString(), owner, token) == 1;
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
            VALUES (?,?,?,?,?,?,?,?,?,0,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=id
            """, id.toString(), orderId.toString(), paymentId.toString(), provider, idempotencyKey,
            sourceType, sourceId == null ? null : sourceId.toString(), paidAmountFen, amountFen, requestHash);
        return new RefundInsert(id, changed == 1);
    }

    /** 按 provider reference 查询退款回调对应的业务记录。 */
    public RefundRecord findRefundByProviderReference(String provider, String reference) {
        return jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status,request_hash,response_utf8 " +
                "FROM refund_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"),
                rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status"),
                rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            provider, reference);
    }

    /** 回调退款终态 CAS；调用方负责与额度、响应和 Outbox 共用事务。 */
    public boolean markRefundTerminalByCallback(String provider, String reference, long amountFen, String status) {
        return jdbc.update("UPDATE refund_order SET status=?,updated_at=CURRENT_TIMESTAMP(6) " +
                "WHERE provider=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')",
            status, provider, reference, amountFen) == 1;
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

    /**
     * 支付超时后 provider 迟到成功的安全收敛：保留成功事实，但取消订单只能进入补偿退款，
     * 不能再次推进交付。订单行锁保证退款额度和 Outbox 只创建一次。
     */
    public UUID recordLatePaymentSuccessAfterCancellation(UUID paymentId, UUID orderId, String providerReference,
                                                           long amountFen, String owner, String token) {
        String orderStatus = jdbc.query("SELECT status FROM trade_order WHERE id=? FOR UPDATE",
            rs -> rs.next() ? rs.getString(1) : null, orderId.toString());
        if (!"CANCELLED".equals(orderStatus)) return null;
        String key = "late-payment-" + paymentId;
        RefundRecord existing = findRefundByKey(orderId, key);
        if (existing != null) return existing.id();
        PaymentRecord current = findPayment(paymentId);
        String provider = current == null || current.provider() == null ? "unknown" : current.provider();
        int paymentChanged = jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=amount_fen,status='SUCCEEDED',"
                + "reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND order_id=? AND amount_fen=? AND (provider_reference=? OR provider_reference IS NULL) "
                + "AND ((status='SUCCEEDED' AND reconcile_owner IS NULL) "
                + "OR (status IN ('PENDING','UNKNOWN','SUCCEEDED') AND reconcile_owner=? AND reconcile_token=? AND reconcile_lease_until>CURRENT_TIMESTAMP(6)) "
                + "OR (status='UNKNOWN' AND provider_reference IS NULL))",
            providerReference, paymentId.toString(), orderId.toString(), amountFen, providerReference, owner, token);
        if (paymentChanged == 0) {
            PaymentRecord payment = findPayment(paymentId);
            if (payment == null || !"SUCCEEDED".equals(payment.status())) throw new IllegalStateException("迟到支付成功事实未能落库");
        }
        if (!reserveRefund(paymentId, amountFen)) throw new IllegalStateException("迟到支付补偿退款额度预占失败");
        RefundInsert refund = insertRefund(orderId, paymentId, provider,
            key, "LATE_PAYMENT", null, amountFen, amountFen);
        UUID refundId = refund.id();
        String payload = "{\"refundId\":\"" + refundId + "\",\"orderId\":\"" + orderId
            + "\",\"paymentId\":\"" + paymentId + "\",\"amountFen\":" + amountFen + ",\"reason\":\"LATE_PAYMENT\"}";
        UUID eventId = UUID.nameUUIDFromBytes(("late-payment-refund:" + refundId).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) "
                + "VALUES (?,?,?,?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE id=id",
            eventId.toString(), eventId.toString(), "REFUND_REQUESTED", refundId.toString(), 1L, payload);
        return refundId;
    }

    /** 回调未携带本地 reference 时，使用 provider、订单和金额唯一锁定 UNKNOWN 支付。 */
    public UUID recordPaymentSuccessByOrder(UUID orderId, String provider, String providerReference, long amountFen) {
        PaymentRecord referenceOwner = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 "
                + "FROM payment_order WHERE provider=? AND provider_reference=? FOR UPDATE",
            rs -> rs.next() ? new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")) : null,
            provider, providerReference);
        if (referenceOwner != null) return null;
        List<PaymentRecord> payments = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,request_hash,response_utf8 "
                + "FROM payment_order WHERE order_id=? AND provider=? AND amount_fen=? AND provider_reference IS NULL "
                + "AND status='UNKNOWN' ORDER BY created_at,id FOR UPDATE",
            (rs, rowNum) -> new PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status"), rs.getBytes("request_hash"), rs.getBytes("response_utf8")),
            orderId.toString(), provider, amountFen);
        if (payments.size() != 1) return null;
        PaymentRecord payment = payments.get(0);
        int changed = jdbc.update("UPDATE payment_order SET provider_reference=?,paid_amount_fen=amount_fen,status='SUCCEEDED',"
                + "reconcile_owner=NULL,reconcile_token=NULL,reconcile_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND order_id=? AND provider=? AND amount_fen=? AND provider_reference IS NULL AND status='UNKNOWN'",
            providerReference, payment.id().toString(), orderId.toString(), provider, amountFen);
        if (changed == 1) return payment.id();
        PaymentRecord current = findPayment(payment.id());
        return current != null && "SUCCEEDED".equals(current.status())
            && providerReference.equals(current.providerReference()) ? current.id() : null;
    }

    public void saveRefundRequest(UUID refundId, byte[] requestHash) {
        jdbc.update("UPDATE refund_order SET request_hash=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", requestHash, refundId.toString());
    }

    public void saveRefundResponse(UUID refundId, byte[] responseUtf8) {
        jdbc.update("UPDATE refund_order SET response_utf8=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", responseUtf8, refundId.toString());
    }

    public boolean claimRefundRequest(UUID refundId) {
        return claimInitialRefundAttempt(refundId, "refund-request", UUID.randomUUID().toString());
    }

    public boolean claimInitialRefundAttempt(UUID refundId, String owner, String token) {
        return jdbc.update("""
            UPDATE refund_order
               SET create_attempted_at=CURRENT_TIMESTAMP(6),
                   reconcile_owner=?, reconcile_token=?,
                   reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),
                   next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),
                   updated_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND create_attempted_at IS NULL
               AND provider_reference IS NULL AND status='REQUESTED'
            """, owner, token, refundId.toString()) == 1;
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
    public record OrderRecord(UUID id, long amountFen, String status) {}
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
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("provider", callback.provider());
            payload.put("providerEventId", callback.providerEventId());
            payload.put("type", callback.type().name());
            payload.put("providerReference", callback.providerReference());
            payload.put("amountFen", callback.amountFen());
            payload.put("status", callback.status());
            payload.put("occurredAt", callback.occurredAt().toString());
            if (callback.orderId() != null) payload.put("orderId", callback.orderId());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) { throw new IllegalStateException("回调摘要序列化失败", e); }
    }
}
