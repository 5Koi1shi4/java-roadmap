package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

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
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public PaymentService(JdbcPaymentRepository repository, PaymentGateway gateway, JdbcTemplate jdbc,
                          @Value("${campus.market.payment.provider:simulated}") String provider,
                          PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.repository = Objects.requireNonNull(repository, "支付仓储不能为空");
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.provider = Objects.requireNonNull(provider, "支付提供方不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.mapper = Objects.requireNonNull(mapper, "JSON序列化器不能为空");
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
        if (existing.responseUtf8() != null && ("SUCCEEDED".equals(existing.status()) || "FAILED".equals(existing.status())))
            return new PaymentResult(paymentId, existing.providerReference(), existing.status(), existing.responseUtf8());
        if (existing.providerReference() != null) return new PaymentResult(paymentId, existing.providerReference(), existing.status(), existing.responseUtf8());
        if (!repository.claimPaymentRequest(paymentId)) {
            JdbcPaymentRepository.PaymentRecord claimed = repository.findPayment(paymentId);
            return new PaymentResult(paymentId, claimed.providerReference(), claimed.status(), claimed.responseUtf8());
        }
        try {
            PaymentGateway.PaymentCreated created = gateway.createPayment(new PaymentGateway.CreatePaymentRequest(orderId,
                Money.ofFen(order.amountFen()), idempotencyKey));
            if (created.status().amountFen() != order.amountFen()) throw new IllegalStateException("支付提供方金额不匹配");
            repository.bindProviderPayment(paymentId, created.providerReference(), created.status().status());
            byte[] response = response(paymentId, created.providerReference(), created.status().status().name());
            repository.savePaymentResponse(paymentId, response);
            return new PaymentResult(paymentId, created.providerReference(), created.status().status().name(), response);
        } catch (RuntimeException unavailable) {
            repository.markPaymentUnknown(paymentId);
            repository.savePaymentResponse(paymentId, response(paymentId, null, "UNKNOWN"));
            throw unavailable;
        }
    }

    public PaymentResult queryPayment(UUID paymentId) {
        JdbcPaymentRepository.PaymentRecord row = repository.findPayment(paymentId);
        return row == null ? null : new PaymentResult(row.id(), row.providerReference(), row.status(), row.responseUtf8());
    }

    /** 主动对账：未知/待定状态只查询原 provider reference，不重新创建支付。 */
    public PaymentResult reconcilePayment(UUID paymentId) {
        String token = UUID.randomUUID().toString();
        if (!repository.claimPaymentReconciliationDirect(paymentId, "direct-payment-reconciler", token)) return queryPayment(paymentId);
        return reconcilePayment(paymentId, "direct-payment-reconciler", token);
    }

    public PaymentResult reconcilePayment(UUID paymentId, String owner, String token) {
        JdbcPaymentRepository.PaymentRecord row = repository.findPayment(paymentId);
        if (row == null || ("SUCCEEDED".equals(row.status()) || "FAILED".equals(row.status()))) return queryPayment(paymentId);
        PaymentGateway.PaymentStatus status = row.providerReference() == null
            ? gateway.queryPaymentByIdempotencyKey(row.idempotencyKey())
            : gateway.queryPayment(row.providerReference());
        if (status.amountFen() != row.amountFen() || status.providerReference() == null
            || (row.providerReference() != null && !row.providerReference().equals(status.providerReference()))) return queryPayment(paymentId);
        if (status.status() == PaymentGateway.PaymentStatus.Status.SUCCEEDED)
            transactions().execute(ignored -> { if (repository.markPaymentSucceeded(paymentId, row.providerReference() == null ? status.providerReference() : row.providerReference(), status.providerReference(), status.amountFen(), owner, token)) {
                jdbc.update("UPDATE trade_order o JOIN payment_order p ON p.order_id=o.id SET o.paid_amount_fen=p.paid_amount_fen,o.status='AWAITING_HANDOFF',o.updated_at=CURRENT_TIMESTAMP(6) WHERE p.id=? AND o.status='PENDING_PAYMENT'", row.id().toString());
                repository.insertPaymentEvent("PAYMENT_SUCCEEDED", row.id(), json(java.util.Map.of("paymentId", row.id(), "orderId", row.orderId(), "amountFen", status.amountFen()))); }
                return null; });
        else if (status.status() == PaymentGateway.PaymentStatus.Status.FAILED)
            transactions().execute(ignored -> { if (repository.markPaymentFailed(paymentId, row.providerReference() == null ? status.providerReference() : row.providerReference(), owner, token))
                repository.insertPaymentEvent("PAYMENT_FAILED", row.id(), json(java.util.Map.of("paymentId", row.id()))); return null; });
        return queryPayment(paymentId);
    }

    private TransactionTemplate transactions() {
        if (transactions == null) throw new IllegalStateException("对账事务管理器未装配");
        return transactions;
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
                        json(java.util.Map.of("paymentId", payment.id(), "orderId", payment.orderId(), "amountFen", callback.amountFen())));
                }
            }
        }
        repository.completeCallback(callback.provider(), callback.providerEventId());
        return new CallbackResult(true, true);
    }

    public record PaymentResult(UUID paymentId, String providerReference, String status, byte[] responseUtf8) {
        public PaymentResult(UUID paymentId, String providerReference, String status) { this(paymentId, providerReference, status, null); }
    }
    public record CallbackResult(boolean idempotentSuccess, boolean firstSeen) {}
    private record OrderAmount(long amountFen) {}
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
    private byte[] response(UUID id, String reference, String status) {
        return jsonBytes(java.util.Map.of("paymentId", id, "providerReference", reference == null ? "" : reference, "status", status));
    }
    private String json(Object value) { return new String(jsonBytes(value), StandardCharsets.UTF_8); }
    private byte[] jsonBytes(Object value) {
        try { return mapper.writeValueAsBytes(value); } catch (Exception e) { throw new IllegalStateException("支付事件序列化失败", e); }
    }
    public static class IdempotencyConflictException extends RuntimeException { }
}
