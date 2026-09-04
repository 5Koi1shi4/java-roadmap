package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.shared.Money;
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
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public RefundService(JdbcPaymentRepository repository, PaymentGateway gateway, JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "支付仓储不能为空");
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount) {
        return requestRefund(orderId, idempotencyKey, amount, "ORDER", null);
    }

    public RefundResult queryRefund(UUID refundId) {
        JdbcPaymentRepository.RefundRecord row = repository.findRefund(refundId);
        return row == null ? null : new RefundResult(row.id(), row.providerReference(), row.status());
    }

    public RefundResult reconcileRefund(UUID refundId) {
        JdbcPaymentRepository.RefundRecord row = repository.findRefund(refundId);
        if (row == null || "SUCCEEDED".equals(row.status()) || "FAILED".equals(row.status())) return queryRefund(refundId);
        PaymentGateway.RefundStatus result = row.providerReference() == null
            ? gateway.queryRefundByIdempotencyKey(row.idempotencyKey()) : gateway.queryRefund(row.providerReference());
        if (result.amountFen() != row.amountFen()) return queryRefund(refundId);
        if (result.amountFen() == row.amountFen() && result.status() == PaymentGateway.RefundStatus.Status.SUCCEEDED && result.providerReference() != null)
            transactions.execute(ignored -> { settleTerminal(row, "SUCCEEDED", result.providerReference()); return null; });
        else if (result.amountFen() == row.amountFen() && result.status() == PaymentGateway.RefundStatus.Status.FAILED)
            transactions.execute(ignored -> { settleTerminal(row, "FAILED", result.providerReference()); return null; });
        return queryRefund(refundId);
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId) {
        String lockKey = orderId + "|" + idempotencyKey;
        Object lock = idempotencyLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            return requestRefundInternal(orderId, idempotencyKey, amount, sourceType, sourceId);
        }
    }

    private RefundResult requestRefundInternal(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId) {
        byte[] requestHash = digest(orderId + "|" + idempotencyKey + "|" + amount.fen() + "|" + sourceType + "|" + sourceId);
        RefundIntent intent = transactions.execute(status -> prepare(orderId, idempotencyKey, amount, sourceType, sourceId, requestHash));
        if (intent.existing() != null) return intent.existing();
        PaymentGateway.RefundCreated created;
        try {
            created = gateway.requestRefund(new PaymentGateway.CreateRefundRequest(orderId,
                intent.paymentReference(), amount, idempotencyKey));
        } catch (RuntimeException failure) {
            return transactions.execute(status -> finish(intent.refundId(), intent.paymentId(), amount.fen(), null,
                PaymentGateway.RefundStatus.Status.UNKNOWN));
        }
        return transactions.execute(status -> {
            RefundResult result = finish(intent.refundId(), intent.paymentId(), amount.fen(),
                created.providerReference(), created.status());
            repository.saveRefundResponse(intent.refundId(), ("{\"refundId\":\"" + intent.refundId() + "\",\"providerReference\":\""
                + created.providerReference() + "\",\"status\":\"" + created.status().name() + "\"}").getBytes(StandardCharsets.UTF_8));
            return result;
        });
    }

    private RefundIntent prepare(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId, byte[] requestHash) {
        JdbcPaymentRepository.PaymentRecord payment = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
            rs -> rs.next() ? new JdbcPaymentRepository.PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status")) : null, orderId.toString());
        if (payment == null || payment.providerReference() == null) throw new IllegalStateException("订单尚未支付成功");
        if (amount.fen() <= 0 || amount.fen() > payment.paidAmountFen()) throw new IllegalArgumentException("退款金额超出实付金额");
        // 幂等重试先复用原退款记录，避免重复占额或第二次调用提供方。
        JdbcPaymentRepository.RefundRecord existing = repository.findRefundByKey(orderId, idempotencyKey);
        if (existing != null) {
            if (existing.amountFen() != amount.fen() || (existing.requestHash() != null && !MessageDigest.isEqual(existing.requestHash(), requestHash))) throw new IdempotencyConflictException();
            return new RefundIntent(null, null, null, new RefundResult(existing.id(), existing.providerReference(), existing.status()));
        }
        if (!repository.reserveRefund(payment.id(), amount.fen())) {
            JdbcPaymentRepository.RefundRecord concurrent = repository.findRefundByKey(orderId, idempotencyKey);
            if (concurrent != null && concurrent.amountFen() == amount.fen()
                && (concurrent.requestHash() == null || MessageDigest.isEqual(concurrent.requestHash(), requestHash))) {
                return new RefundIntent(null, null, null, new RefundResult(concurrent.id(), concurrent.providerReference(), concurrent.status()));
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
            return new RefundIntent(null, null, null, new RefundResult(raced.id(), raced.providerReference(), raced.status()));
        }
        repository.claimRefundRequest(claim.id());
        return new RefundIntent(claim.id(), payment.id(), payment.providerReference(), null);
    }

    private RefundResult finish(UUID refundId, UUID paymentId, long amountFen, String reference, PaymentGateway.RefundStatus.Status status) {
        String storedStatus = status == PaymentGateway.RefundStatus.Status.PENDING ? "PROCESSING" : status.name();
        int changed = jdbc.update("UPDATE refund_order SET status=?,provider_reference=COALESCE(?,provider_reference),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", storedStatus, reference, refundId.toString());
        if (changed == 1 && status == PaymentGateway.RefundStatus.Status.SUCCEEDED) {
            repository.completeRefund(paymentId, amountFen);
            repository.insertPaymentEvent("REFUND_SUCCEEDED", refundId, "{\"refundId\":\"" + refundId + "\",\"amountFen\":" + amountFen + "}");
        }
        if (changed == 1 && status == PaymentGateway.RefundStatus.Status.FAILED) {
            repository.releaseRefund(paymentId, amountFen);
            repository.insertPaymentEvent("REFUND_FAILED", refundId, "{\"refundId\":\"" + refundId + "\",\"amountFen\":" + amountFen + "}");
        }
        if (changed == 1 && status == PaymentGateway.RefundStatus.Status.UNKNOWN) repository.saveRefundResponse(refundId, ("{\"refundId\":\"" + refundId + "\",\"status\":\"UNKNOWN\"}").getBytes(StandardCharsets.UTF_8));
        return new RefundResult(refundId, reference, storedStatus);
    }

    private void settleTerminal(JdbcPaymentRepository.RefundRecord row, String status, String reference) {
        if (jdbc.update("UPDATE refund_order SET status=?,provider_reference=COALESCE(?,provider_reference),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND (provider_reference=? OR provider_reference IS NULL) AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", status, reference, row.id().toString(), row.providerReference(), row.amountFen()) == 1) {
            if ("SUCCEEDED".equals(status)) repository.completeRefund(row.paymentId(), row.amountFen());
            else repository.releaseRefund(row.paymentId(), row.amountFen());
            repository.insertPaymentEvent("REFUND_" + status, row.id(), "{\"refundId\":\"" + row.id() + "\",\"amountFen\":" + row.amountFen() + "}");
        }
    }

    @Transactional
    public void handleCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        if (!"REFUND".equals(callback.type().name())) return;
        if (!repository.recordCallback(callback, rawBody)) return;
        JdbcPaymentRepository.RefundRecord refund = jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status FROM refund_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new JdbcPaymentRepository.RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")), UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status")) : null,
            callback.provider(), callback.providerReference());
        if (refund == null) { repository.failCallback(callback.provider(), callback.providerEventId()); return; }
        if (callback.amountFen() != refund.amountFen()) { repository.failCallback(callback.provider(), callback.providerEventId()); return; }
        if ("SUCCEEDED".equals(callback.status())) {
            if (jdbc.update("UPDATE refund_order SET status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", refund.id().toString(), callback.provider(), callback.providerReference(), callback.amountFen()) == 1 && repository.completeRefund(refund.paymentId(), refund.amountFen())) {
                repository.insertPaymentEvent("REFUND_SUCCEEDED", refund.id(),
                    "{\"refundId\":\"" + refund.id() + "\",\"orderId\":\"" + refund.orderId() + "\",\"amountFen\":" + refund.amountFen() + "}");
            }
        } else if ("FAILED".equals(callback.status())) {
            if (jdbc.update("UPDATE refund_order SET status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", refund.id().toString(), callback.provider(), callback.providerReference(), callback.amountFen()) == 1 && repository.releaseRefund(refund.paymentId(), refund.amountFen())) {
            }
        }
        repository.completeCallback(callback.provider(), callback.providerEventId());
    }

    public record RefundResult(UUID refundId, String providerReference, String status) {}
    private record RefundIntent(UUID refundId, UUID paymentId, String paymentReference, RefundResult existing) {}
    public static class RefundLimitExceededException extends RuntimeException { }
    public static class IdempotencyConflictException extends RuntimeException { public IdempotencyConflictException() { } public IdempotencyConflictException(String message) { super(message); } }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
}
