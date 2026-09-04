package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/** 退款申请与额度预占；未知网关结果只进入查询，不自动创建第二笔退款。 */
@Service
@Profile("!test")
public class RefundService {
    private final JdbcPaymentRepository repository;
    private final PaymentGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public RefundService(JdbcPaymentRepository repository, PaymentGateway gateway, JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.repository = Objects.requireNonNull(repository, "支付仓储不能为空");
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.mapper = Objects.requireNonNull(mapper, "JSON序列化器不能为空");
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount) {
        return requestRefund(orderId, idempotencyKey, amount, "ORDER", null, new byte[0]);
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount, byte[] rawRequest) {
        return requestRefund(orderId, idempotencyKey, amount, "ORDER", null, rawRequest);
    }

    public RefundResult queryRefund(UUID refundId) {
        JdbcPaymentRepository.RefundRecord row = repository.findRefund(refundId);
        return row == null ? null : new RefundResult(row.id(), row.providerReference(), row.status(), row.responseUtf8());
    }

    public RefundResult reconcileRefund(UUID refundId) {
        String token = UUID.randomUUID().toString();
        if (!repository.claimRefundReconciliationDirect(refundId, "direct-refund-reconciler", token)) return queryRefund(refundId);
        return reconcileRefund(refundId, "direct-refund-reconciler", token);
    }

    public RefundResult reconcileRefund(UUID refundId, String owner, String token) {
        JdbcPaymentRepository.RefundRecord row = repository.findRefund(refundId);
        if (row == null || "SUCCEEDED".equals(row.status()) || "FAILED".equals(row.status())) return queryRefund(refundId);
        PaymentGateway.RefundStatus result = row.providerReference() == null
            ? gateway.queryRefundByIdempotencyKey(row.idempotencyKey()) : gateway.queryRefund(row.providerReference());
        if (result.amountFen() != row.amountFen() || result.providerReference() == null
            || (row.providerReference() != null && !row.providerReference().equals(result.providerReference()))) return queryRefund(refundId);
        if (result.status() == PaymentGateway.RefundStatus.Status.SUCCEEDED)
            transactions.execute(ignored -> { settleTerminal(row, "SUCCEEDED", result.providerReference(), owner, token); return null; });
        else if (result.status() == PaymentGateway.RefundStatus.Status.FAILED)
            transactions.execute(ignored -> { settleTerminal(row, "FAILED", result.providerReference(), owner, token); return null; });
        return queryRefund(refundId);
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId) {
        return requestRefund(orderId, idempotencyKey, amount, sourceType, sourceId, new byte[0]);
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId, byte[] rawRequest) {
        String lockKey = orderId + "|" + idempotencyKey;
        Object lock = idempotencyLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            return requestRefundInternal(orderId, idempotencyKey, amount, sourceType, sourceId, rawRequest);
        }
    }

    private RefundResult requestRefundInternal(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId, byte[] rawRequest) {
        byte[] body = rawRequest == null ? new byte[0] : rawRequest.clone();
        RefundIntent intent = transactions.execute(status -> prepare(orderId, idempotencyKey, amount, sourceType, sourceId, body));
        if (intent.existing() != null) return intent.existing();
        PaymentGateway.RefundCreated created;
        try {
            created = gateway.requestRefund(new PaymentGateway.CreateRefundRequest(orderId,
                intent.paymentReference(), amount, idempotencyKey));
        } catch (RuntimeException failure) {
            return transactions.execute(status -> {
                RefundResult result = finish(intent.refundId(), intent.paymentId(), amount.fen(), null,
                    PaymentGateway.RefundStatus.Status.UNKNOWN);
                byte[] response = response(result.refundId(), result.providerReference(), result.status());
                repository.saveRefundResponse(intent.refundId(), response);
                return new RefundResult(result.refundId(), result.providerReference(), result.status(), response);
            });
        }
        return transactions.execute(status -> {
            RefundResult result = finish(intent.refundId(), intent.paymentId(), amount.fen(),
                created.providerReference(), created.status());
            byte[] response = response(result.refundId(), result.providerReference(), result.status());
            repository.saveRefundResponse(intent.refundId(), response);
            result = new RefundResult(result.refundId(), result.providerReference(), result.status(), response);
            return result;
        });
    }

    private RefundIntent prepare(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId, byte[] rawRequest) {
        JdbcPaymentRepository.PaymentRecord payment = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
            rs -> rs.next() ? new JdbcPaymentRepository.PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status")) : null, orderId.toString());
        if (payment == null || payment.providerReference() == null) throw new IllegalStateException("订单尚未支付成功");
        if (amount.fen() <= 0 || amount.fen() > payment.paidAmountFen()) throw new IllegalArgumentException("退款金额超出实付金额");
        byte[] requestHash = digest(payment.provider() + "|CNY|" + payment.providerReference() + "|" + orderId + "|"
            + amount.fen() + "|" + sourceType + "|" + sourceId + "|" + idempotencyKey + "|" +
            java.util.Base64.getEncoder().encodeToString(rawRequest));
        // 幂等重试先复用原退款记录，避免重复占额或第二次调用提供方。
        JdbcPaymentRepository.RefundRecord existing = repository.findRefundByKey(orderId, idempotencyKey);
        if (existing != null) {
            if (existing.amountFen() != amount.fen() || (existing.requestHash() != null && !MessageDigest.isEqual(existing.requestHash(), requestHash))) throw new IdempotencyConflictException();
                return new RefundIntent(null, null, null, new RefundResult(existing.id(), existing.providerReference(), existing.status(), existing.responseUtf8()));
        }
        if (!repository.reserveRefund(payment.id(), amount.fen())) {
            JdbcPaymentRepository.RefundRecord concurrent = repository.findRefundByKey(orderId, idempotencyKey);
            if (concurrent != null && concurrent.amountFen() == amount.fen()
                && (concurrent.requestHash() == null || MessageDigest.isEqual(concurrent.requestHash(), requestHash))) {
                return new RefundIntent(null, null, null, new RefundResult(concurrent.id(), concurrent.providerReference(), concurrent.status(), concurrent.responseUtf8()));
            }
            throw new RefundLimitExceededException();
        }
        JdbcPaymentRepository.RefundInsert claim = repository.insertRefund(orderId, payment.id(), payment.provider(), idempotencyKey, sourceType, sourceId,
            payment.paidAmountFen(), amount.fen(), requestHash);
        if (!claim.inserted()) {
            // 并发请求可能先占额后抢到同一唯一键；释放本次临时占额并重放原结果。
            repository.releaseRefund(payment.id(), amount.fen());
            JdbcPaymentRepository.RefundRecord raced = repository.findRefundByKey(orderId, idempotencyKey);
            if (raced == null) throw new IdempotencyConflictException("退款幂等记录未找到");
            if (raced.amountFen() != amount.fen()) throw new IdempotencyConflictException("退款金额不一致");
            if (raced.requestHash() != null && !MessageDigest.isEqual(raced.requestHash(), requestHash)) throw new IdempotencyConflictException("退款请求指纹不一致");
            return new RefundIntent(null, null, null, new RefundResult(raced.id(), raced.providerReference(), raced.status(), raced.responseUtf8()));
        }
        repository.claimRefundRequest(claim.id());
        return new RefundIntent(claim.id(), payment.id(), payment.providerReference(), null);
    }

    private RefundResult finish(UUID refundId, UUID paymentId, long amountFen, String reference, PaymentGateway.RefundStatus.Status status) {
        String storedStatus = status == PaymentGateway.RefundStatus.Status.PENDING ? "PROCESSING" : status.name();
        int changed = jdbc.update("UPDATE refund_order SET status=?,provider_reference=COALESCE(?,provider_reference),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", storedStatus, reference, refundId.toString());
        if (changed != 1) throw new IllegalStateException("退款状态更新 CAS 失败，等待重试");
        if (status == PaymentGateway.RefundStatus.Status.SUCCEEDED) {
            if (!repository.completeRefund(paymentId, amountFen)) throw new IllegalStateException("退款额度结转失败，等待对账");
            repository.insertPaymentEvent("REFUND_SUCCEEDED", refundId, json(java.util.Map.of("refundId", refundId, "amountFen", amountFen)));
        }
        if (status == PaymentGateway.RefundStatus.Status.FAILED) {
            if (!repository.releaseRefund(paymentId, amountFen)) throw new IllegalStateException("退款额度释放失败，等待对账");
            repository.insertPaymentEvent("REFUND_FAILED", refundId, json(java.util.Map.of("refundId", refundId, "amountFen", amountFen)));
        }
        if (status == PaymentGateway.RefundStatus.Status.UNKNOWN) repository.saveRefundResponse(refundId, response(refundId, reference, "UNKNOWN"));
        return new RefundResult(refundId, reference, storedStatus);
    }

    private void settleTerminal(JdbcPaymentRepository.RefundRecord row, String status, String reference, String owner, String token) {
        if (!repository.markRefundTerminal(row.id(), row.providerReference(), reference, status, row.amountFen(), owner, token))
            throw new IllegalStateException("退款状态结算 CAS 失败，等待重试");
        boolean settled = "SUCCEEDED".equals(status) ? repository.completeRefund(row.paymentId(), row.amountFen()) : repository.releaseRefund(row.paymentId(), row.amountFen());
        if (!settled) throw new IllegalStateException("退款聚合结转失败，等待重试");
        repository.insertPaymentEvent("REFUND_" + status, row.id(), json(java.util.Map.of("refundId", row.id(), "amountFen", row.amountFen())));
    }

    @Transactional
    public void handleCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        if (!"REFUND".equals(callback.type().name())) return;
        if (!repository.recordCallback(callback, rawBody)) return;
        JdbcPaymentRepository.RefundRecord refund = jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status FROM refund_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new JdbcPaymentRepository.RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")), UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status")) : null,
            callback.provider(), callback.providerReference());
        if (refund == null) {
            if (!repository.failCallback(callback.provider(), callback.providerEventId())) throw new IllegalStateException("退款回调失败 CAS 失败，等待重试");
            return;
        }
        if (callback.amountFen() != refund.amountFen()) {
            if (!repository.failCallback(callback.provider(), callback.providerEventId())) throw new IllegalStateException("退款回调失败 CAS 失败，等待重试");
            return;
        }
        if ("SUCCEEDED".equals(callback.status())) {
            int changed = jdbc.update("UPDATE refund_order SET status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", refund.id().toString(), callback.provider(), callback.providerReference(), callback.amountFen());
            if (changed != 1) throw new IllegalStateException("退款状态结算 CAS 失败，等待重试");
            if (!repository.completeRefund(refund.paymentId(), refund.amountFen())) throw new IllegalStateException("退款额度结转失败，等待重试");
            repository.insertPaymentEvent("REFUND_SUCCEEDED", refund.id(), json(java.util.Map.of("refundId", refund.id(), "orderId", refund.orderId(), "amountFen", refund.amountFen())));
        } else if ("FAILED".equals(callback.status())) {
            int changed = jdbc.update("UPDATE refund_order SET status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", refund.id().toString(), callback.provider(), callback.providerReference(), callback.amountFen());
            if (changed != 1) throw new IllegalStateException("退款状态结算 CAS 失败，等待重试");
            if (!repository.releaseRefund(refund.paymentId(), refund.amountFen())) throw new IllegalStateException("退款额度释放失败，等待重试");
            repository.insertPaymentEvent("REFUND_FAILED", refund.id(), json(java.util.Map.of("refundId", refund.id(), "orderId", refund.orderId(), "amountFen", refund.amountFen())));
        }
        if (!repository.completeCallback(callback.provider(), callback.providerEventId()))
            throw new IllegalStateException("退款回调完成 CAS 失败，等待重试");
    }

    public record RefundResult(UUID refundId, String providerReference, String status, byte[] responseUtf8) {
        public RefundResult(UUID refundId, String providerReference, String status) { this(refundId, providerReference, status, null); }
    }
    private record RefundIntent(UUID refundId, UUID paymentId, String paymentReference, RefundResult existing) {}
    public static class RefundLimitExceededException extends RuntimeException { }
    public static class IdempotencyConflictException extends RuntimeException { public IdempotencyConflictException() { } public IdempotencyConflictException(String message) { super(message); } }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
    private byte[] response(UUID id, String reference, String status) {
        try { return mapper.writeValueAsBytes(java.util.Map.of("refundId", id, "providerReference", reference == null ? "" : reference, "status", status)); }
        catch (Exception e) { throw new IllegalStateException("退款响应序列化失败", e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("退款事件序列化失败", e); }
    }
}
