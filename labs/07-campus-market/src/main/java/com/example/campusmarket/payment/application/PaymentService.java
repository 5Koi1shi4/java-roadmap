package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.shared.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 支付创建与验签回调编排；数据库事务不包住外部网关调用。 */
@Service
@Profile("!test")
public class PaymentService {
    private final JdbcPaymentRepository repository;
    private final PaymentGateway gateway;
    private final JdbcTemplate jdbc;
    private final String provider;

    public PaymentService(JdbcPaymentRepository repository, PaymentGateway gateway, JdbcTemplate jdbc,
                          @Value("${campus.market.payment.provider:simulated}") String provider) {
        this.repository = Objects.requireNonNull(repository, "支付仓储不能为空");
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.provider = Objects.requireNonNull(provider, "支付提供方不能为空");
    }

    public PaymentResult createPayment(UUID orderId, String idempotencyKey) {
        OrderAmount order = jdbc.query("SELECT total_amount_fen FROM trade_order WHERE id=? AND status='PENDING_PAYMENT'",
            rs -> rs.next() ? new OrderAmount(rs.getLong(1)) : null, orderId.toString());
        if (order == null) throw new IllegalStateException("订单不存在或不可支付");
        byte[] requestHash = digest(orderId + ":" + order.amountFen() + ":" + idempotencyKey);
        UUID paymentId = repository.insertPendingPayment(orderId, provider, idempotencyKey, Money.ofFen(order.amountFen()), requestHash);
        JdbcPaymentRepository.PaymentRecord existing = repository.findPayment(paymentId);
        if (existing.requestHash() != null && !MessageDigest.isEqual(existing.requestHash(), requestHash)) {
            throw new IdempotencyConflictException();
        }
        if (existing.providerReference() != null) return new PaymentResult(paymentId, existing.providerReference(), existing.status());
        PaymentGateway.PaymentCreated created = gateway.createPayment(new PaymentGateway.CreatePaymentRequest(orderId,
            Money.ofFen(order.amountFen()), idempotencyKey));
        repository.bindProviderPayment(paymentId, created.providerReference(), created.status().status());
        return new PaymentResult(paymentId, created.providerReference(), created.status().status().name());
    }

    public PaymentResult queryPayment(UUID paymentId) {
        JdbcPaymentRepository.PaymentRecord row = repository.findPayment(paymentId);
        return row == null ? null : new PaymentResult(row.id(), row.providerReference(), row.status());
    }

    /** 主动对账：未知/待定状态只查询原 provider reference，不重新创建支付。 */
    @Transactional
    public PaymentResult reconcilePayment(UUID paymentId) {
        JdbcPaymentRepository.PaymentRecord row = repository.findPayment(paymentId);
        if (row == null || row.providerReference() == null ||
            ("SUCCEEDED".equals(row.status()) || "FAILED".equals(row.status()))) return queryPayment(paymentId);
        PaymentGateway.PaymentStatus status = gateway.queryPayment(row.providerReference());
        if (status.status() == PaymentGateway.PaymentStatus.Status.SUCCEEDED) {
            repository.markPaymentSucceededByReference(row.provider(), row.providerReference(), status.amountFen());
            jdbc.update("UPDATE trade_order o JOIN payment_order p ON p.order_id=o.id SET o.paid_amount_fen=p.paid_amount_fen,o.status='AWAITING_HANDOFF',o.updated_at=CURRENT_TIMESTAMP(6) WHERE p.id=? AND o.status='PENDING_PAYMENT'", row.id().toString());
        } else if (status.status() == PaymentGateway.PaymentStatus.Status.FAILED) {
            jdbc.update("UPDATE payment_order SET status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','UNKNOWN')", row.id().toString());
        }
        return queryPayment(paymentId);
    }

    @Transactional
    public CallbackResult handleCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        if (!repository.recordCallback(callback, rawBody)) return new CallbackResult(true, false);
        if (callback.type() == PaymentGateway.VerifiedCallback.CallbackType.PAYMENT
            && "SUCCEEDED".equals(callback.status())) {
            boolean changed = repository.markPaymentSucceededByReference(callback.provider(), callback.providerReference(), callback.amountFen());
            if (changed) {
                jdbc.update("UPDATE trade_order o JOIN payment_order p ON p.order_id=o.id SET o.paid_amount_fen=p.paid_amount_fen,o.status='AWAITING_HANDOFF',o.updated_at=CURRENT_TIMESTAMP(6) WHERE p.provider=? AND p.provider_reference=? AND o.status='PENDING_PAYMENT'",
                    callback.provider(), callback.providerReference());
                JdbcPaymentRepository.PaymentRecord payment = repository.findPaymentByReference(callback.provider(), callback.providerReference());
                if (payment != null) {
                    repository.insertPaymentEvent("PAYMENT_SUCCEEDED", payment.id(),
                        "{\"paymentId\":\"" + payment.id() + "\",\"orderId\":\"" + payment.orderId() + "\",\"amountFen\":" + callback.amountFen() + "}");
                }
            }
        }
        repository.completeCallback(callback.provider(), callback.providerEventId());
        return new CallbackResult(true, true);
    }

    public record PaymentResult(UUID paymentId, String providerReference, String status) {}
    public record CallbackResult(boolean idempotentSuccess, boolean firstSeen) {}
    private record OrderAmount(long amountFen) {}
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
    public static class IdempotencyConflictException extends RuntimeException { }
}
