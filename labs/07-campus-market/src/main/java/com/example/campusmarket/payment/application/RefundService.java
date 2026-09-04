package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** 退款申请与额度预占；未知网关结果只进入查询，不自动创建第二笔退款。 */
@Service
@Profile("!test")
public class RefundService {
    private final JdbcPaymentRepository repository;
    private final PaymentGateway gateway;
    private final JdbcTemplate jdbc;

    public RefundService(JdbcPaymentRepository repository, PaymentGateway gateway, JdbcTemplate jdbc) {
        this.repository = Objects.requireNonNull(repository, "支付仓储不能为空");
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    @Transactional
    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount) {
        return requestRefund(orderId, idempotencyKey, amount, "ORDER", null);
    }

    @Transactional
    public RefundResult requestRefund(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId) {
        JdbcPaymentRepository.PaymentRecord payment = jdbc.query("SELECT id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
            rs -> rs.next() ? new JdbcPaymentRepository.PaymentRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")),
                rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                rs.getString("provider_reference"), rs.getString("status")) : null, orderId.toString());
        if (payment == null || payment.providerReference() == null) throw new IllegalStateException("订单尚未支付成功");
        if (amount.fen() <= 0 || amount.fen() > payment.paidAmountFen()) throw new IllegalArgumentException("退款金额超出实付金额");
        // 幂等重试先复用原退款记录，避免重复占额或第二次调用提供方。
        JdbcPaymentRepository.RefundRecord existing = repository.findRefundByKey(orderId, idempotencyKey);
        if (existing != null) return new RefundResult(existing.id(), existing.providerReference(), existing.status());
        if (!repository.reserveRefund(payment.id(), amount.fen())) throw new RefundLimitExceededException();
        UUID refundId;
        try {
            refundId = repository.insertRefund(orderId, payment.id(), payment.provider(), idempotencyKey, sourceType, sourceId,
                payment.paidAmountFen(), amount.fen());
            JdbcPaymentRepository.RefundRecord raced = repository.findRefund(refundId);
            if (raced != null && !raced.id().equals(refundId)) {
                repository.releaseRefund(payment.id(), amount.fen());
                return new RefundResult(raced.id(), raced.providerReference(), raced.status());
            }
        } catch (RuntimeException e) {
            repository.releaseRefund(payment.id(), amount.fen());
            throw e;
        }
        PaymentGateway.RefundCreated created = gateway.requestRefund(new PaymentGateway.CreateRefundRequest(orderId,
            payment.providerReference(), amount, idempotencyKey));
        repository.bindRefundProvider(refundId, created.providerReference(), created.status());
        return new RefundResult(refundId, created.providerReference(), created.status().name());
    }

    @Transactional
    public void handleCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        if (!"REFUND".equals(callback.type().name())) return;
        if (!repository.recordCallback(callback, rawBody)) return;
        JdbcPaymentRepository.RefundRecord refund = jdbc.query("SELECT id,order_id,payment_order_id,provider,idempotency_key,amount_fen,provider_reference,status FROM refund_order WHERE provider=? AND provider_reference=?",
            rs -> rs.next() ? new JdbcPaymentRepository.RefundRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("order_id")), UUID.fromString(rs.getString("payment_order_id")), rs.getString("provider"), rs.getString("idempotency_key"), rs.getLong("amount_fen"), rs.getString("provider_reference"), rs.getString("status")) : null,
            callback.provider(), callback.providerReference());
        if (refund == null) return;
        if ("SUCCEEDED".equals(callback.status())) {
            if (repository.completeRefund(refund.paymentId(), refund.amountFen())) {
                jdbc.update("UPDATE refund_order SET status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING')", refund.id().toString());
                repository.insertPaymentEvent("REFUND_SUCCEEDED", refund.id(),
                    "{\"refundId\":\"" + refund.id() + "\",\"orderId\":\"" + refund.orderId() + "\",\"amountFen\":" + refund.amountFen() + "}");
            }
        } else if ("FAILED".equals(callback.status())) {
            if (repository.releaseRefund(refund.paymentId(), refund.amountFen())) {
                jdbc.update("UPDATE refund_order SET status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','PROCESSING')", refund.id().toString());
            }
        }
        repository.completeCallback(callback.provider(), callback.providerEventId());
    }

    public record RefundResult(UUID refundId, String providerReference, String status) {}
    public static class RefundLimitExceededException extends RuntimeException { }
}
