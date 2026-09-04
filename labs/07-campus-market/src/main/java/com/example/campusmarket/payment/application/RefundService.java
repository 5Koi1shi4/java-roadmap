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

/** 退款申请与额度预占；未知网关结果只进入查询，不自动创建第二笔退款。 */
@Service
@Profile("!test")
public class RefundService {
    private final JdbcPaymentRepository repository;
    private final PaymentGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

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

    @Transactional
    public RefundResult reconcileRefund(UUID refundId) {
        JdbcPaymentRepository.RefundRecord row = repository.findRefund(refundId);
        if (row == null || row.providerReference() == null || "SUCCEEDED".equals(row.status()) || "FAILED".equals(row.status())) return queryRefund(refundId);
        PaymentGateway.RefundStatus result = gateway.queryRefund(row.providerReference());
        if (result.amountFen() != row.amountFen()) return queryRefund(refundId);
        if (result.status() == PaymentGateway.RefundStatus.Status.SUCCEEDED) {
            if (jdbc.update("UPDATE refund_order SET status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", row.id().toString(), row.providerReference(), row.amountFen()) == 1) repository.completeRefund(row.paymentId(), row.amountFen());
        } else if (result.status() == PaymentGateway.RefundStatus.Status.FAILED) {
            if (jdbc.update("UPDATE refund_order SET status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND provider_reference=? AND amount_fen=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", row.id().toString(), row.providerReference(), row.amountFen()) == 1) repository.releaseRefund(row.paymentId(), row.amountFen());
        }
        return queryRefund(refundId);
    }

    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId) {
        RefundIntent intent = transactions.execute(status -> prepare(orderId, idempotencyKey, amount, sourceType, sourceId));
        if (intent.existing() != null) return intent.existing();
        PaymentGateway.RefundCreated created;
        try {
            created = gateway.requestRefund(new PaymentGateway.CreateRefundRequest(orderId,
                intent.paymentReference(), amount, idempotencyKey));
        } catch (RuntimeException failure) {
            return transactions.execute(status -> finish(intent.refundId(), intent.paymentId(), amount.fen(), null,
                PaymentGateway.RefundStatus.Status.UNKNOWN));
        }
        return transactions.execute(status -> finish(intent.refundId(), intent.paymentId(), amount.fen(),
            created.providerReference(), created.status()));
    }

    private RefundIntent prepare(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId) {
        JdbcPaymentRepository.PaymentRecord payment = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
            rs -> rs.next() ? new JdbcPaymentRepository.PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status")) : null, orderId.toString());
        if (payment == null || payment.providerReference() == null) throw new IllegalStateException("订单尚未支付成功");
        if (amount.fen() <= 0 || amount.fen() > payment.paidAmountFen()) throw new IllegalArgumentException("退款金额超出实付金额");
        // 幂等重试先复用原退款记录，避免重复占额或第二次调用提供方。
        JdbcPaymentRepository.RefundRecord existing = repository.findRefundByKey(orderId, idempotencyKey);
        if (existing != null) {
            if (existing.amountFen() != amount.fen()) throw new IdempotencyConflictException();
            return new RefundIntent(null, null, null, new RefundResult(existing.id(), existing.providerReference(), existing.status()));
        }
        if (!repository.reserveRefund(payment.id(), amount.fen())) throw new RefundLimitExceededException();
        UUID refundId = repository.insertRefund(orderId, payment.id(), payment.provider(), idempotencyKey, sourceType, sourceId,
            payment.paidAmountFen(), amount.fen());
        return new RefundIntent(refundId, payment.id(), payment.providerReference(), null);
    }

    private RefundResult finish(UUID refundId, UUID paymentId, long amountFen, String reference, PaymentGateway.RefundStatus.Status status) {
        if (reference != null) repository.bindRefundProvider(refundId, reference, status);
        jdbc.update("UPDATE refund_order SET status=?,provider_reference=COALESCE(?,provider_reference),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", status.name(), reference, refundId.toString());
        return new RefundResult(refundId, reference, status.name());
    }

    @Transactional
    public void handleCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        if (!"REFUND".equals(callback.type().name())) return;
        if (!repository.recordCallback(callback, rawBody)) return;
        JdbcPaymentRepository.RefundRecord refund = jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status FROM refund_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new JdbcPaymentRepository.RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")), UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status")) : null,
            callback.provider(), callback.providerReference());
        if (refund == null) return;
        if (callback.amountFen() != refund.amountFen()) return;
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
    public static class IdempotencyConflictException extends RuntimeException { }
}
