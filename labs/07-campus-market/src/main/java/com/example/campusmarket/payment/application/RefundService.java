package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public RefundService(JdbcPaymentRepository repository, PaymentGateway gateway,
                         PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.repository = Objects.requireNonNull(repository, "支付仓储不能为空");
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
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
            transactions.execute(ignored -> { settleTerminal(row.id(), row.paymentId(), row.amountFen(), row.providerReference(), result.providerReference(), "SUCCEEDED", owner, token); return null; });
        else if (result.status() == PaymentGateway.RefundStatus.Status.FAILED)
            transactions.execute(ignored -> { settleTerminal(row.id(), row.paymentId(), row.amountFen(), row.providerReference(), result.providerReference(), "FAILED", owner, token); return null; });
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
        String owner = "refund-request-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        RefundIntent intent = transactions.execute(status -> prepare(orderId, idempotencyKey, amount, sourceType, sourceId, body, owner, token));
        if (intent.existing() != null) {
            if ("REQUESTED".equals(intent.existing().status())) return retryExisting(orderId, intent.existing());
            return intent.existing();
        }
        PaymentGateway.RefundCreated created;
        try {
            created = gateway.requestRefund(new PaymentGateway.CreateRefundRequest(orderId,
                intent.paymentReference(), amount, idempotencyKey));
        } catch (RuntimeException failure) {
            return finishUnknown(intent);
        }
        return finishOwned(intent, created);
    }

    private RefundIntent prepare(UUID orderId, String idempotencyKey, Money amount, String sourceType, UUID sourceId,
                                 byte[] rawRequest, String owner, String token) {
        JdbcPaymentRepository.OrderRecord order = repository.lockOrderForRefund(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        JdbcPaymentRepository.PaymentRecord payment = repository.findLatestSuccessfulPaymentByOrderForUpdate(orderId);
        if (payment == null || payment.providerReference() == null) throw new IllegalStateException("订单尚未支付成功");
        if (amount.fen() <= 0 || amount.fen() > payment.paidAmountFen()) throw new IllegalArgumentException("退款金额超出实付金额");
        byte[] requestHash = digest(payment.provider() + "|CNY|" + payment.providerReference() + "|" + orderId + "|"
            + amount.fen() + "|" + sourceType + "|" + sourceId + "|" + idempotencyKey + "|" +
            java.util.Base64.getEncoder().encodeToString(rawRequest));
        // 幂等重试先复用原退款记录，避免重复占额或第二次调用提供方。
        JdbcPaymentRepository.RefundRecord existing = repository.findRefundByKey(orderId, idempotencyKey);
        if (existing != null) {
            if (existing.amountFen() != amount.fen() || (existing.requestHash() != null && !MessageDigest.isEqual(existing.requestHash(), requestHash))) throw new IdempotencyConflictException();
                return new RefundIntent(null, null, null, 0L, null, null, new RefundResult(existing.id(), existing.providerReference(), existing.status(), existing.responseUtf8()));
        }
        if ("SETTLED".equals(order.status())) throw new IllegalStateException("订单已结算，不能创建普通退款");
        if (!repository.reserveRefund(payment.id(), amount.fen())) {
            JdbcPaymentRepository.RefundRecord concurrent = repository.findRefundByKey(orderId, idempotencyKey);
            if (concurrent != null && concurrent.amountFen() == amount.fen()
                && (concurrent.requestHash() == null || MessageDigest.isEqual(concurrent.requestHash(), requestHash))) {
                return new RefundIntent(null, null, null, 0L, null, null, new RefundResult(concurrent.id(), concurrent.providerReference(), concurrent.status(), concurrent.responseUtf8()));
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
            return new RefundIntent(null, null, null, 0L, null, null, new RefundResult(raced.id(), raced.providerReference(), raced.status(), raced.responseUtf8()));
        }
        if (!repository.claimInitialRefundAttempt(claim.id(), owner, token))
            throw new IllegalStateException("退款首次创建权获取失败");
        repository.insertPaymentEvent("REFUND_REQUESTED", claim.id(), json(java.util.Map.of("refundId", claim.id(), "orderId", orderId, "amountFen", amount.fen())));
        return new RefundIntent(claim.id(), payment.id(), payment.providerReference(), amount.fen(), owner, token, null);
    }

    private RefundResult retryExisting(UUID orderId, RefundResult existing) {
        JdbcPaymentRepository.RefundRecord row = repository.findRefund(existing.refundId());
        if (row == null || !"REQUESTED".equals(row.status())) return existing;
        JdbcPaymentRepository.PaymentRecord payment = repository.findPayment(row.paymentId());
        if (payment == null || payment.providerReference() == null) return existing;
        String owner = "refund-retry-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        if (!repository.claimInitialRefundAttempt(row.id(), owner, token)) return queryRefund(row.id());
        try {
            PaymentGateway.RefundCreated created = gateway.requestRefund(new PaymentGateway.CreateRefundRequest(orderId,
                payment.providerReference(), Money.ofFen(row.amountFen()), row.idempotencyKey()));
            return finishOwned(new RefundIntent(row.id(), row.paymentId(), payment.providerReference(), row.amountFen(), owner, token, null), created);
        } catch (RuntimeException failure) {
            return finishUnknown(new RefundIntent(row.id(), row.paymentId(), payment.providerReference(), row.amountFen(), owner, token, null));
        }
    }

    /** 只有首次 claim 的 owner 才能在事务外 IO 返回后提交结果；迟到 owner 直接重放当前记录。 */
    private RefundResult finishOwned(RefundIntent intent, PaymentGateway.RefundCreated created) {
        if (created.providerReference() == null || created.providerReference().isBlank()
            || created.amountFen() != intent.amountFen()) {
            return finishUnknown(intent, created.providerReference());
        }
        String status = created.status().name();
        RefundResult committed = transactions.execute(ignored -> {
            if (created.status() == PaymentGateway.RefundStatus.Status.SUCCEEDED
                || created.status() == PaymentGateway.RefundStatus.Status.FAILED) {
                byte[] saved = settleTerminal(intent.refundId(), intent.paymentId(), intent.amountFen(), null,
                    created.providerReference(), status, intent.owner(), intent.token());
                if (saved == null) return null;
                return new RefundResult(intent.refundId(), created.providerReference(), status, saved);
            }
            if (created.status() == PaymentGateway.RefundStatus.Status.PENDING) {
                if (!repository.markRefundProcessing(intent.refundId(), null, created.providerReference(),
                    intent.amountFen(), intent.owner(), intent.token())) return null;
                byte[] saved = response(intent.refundId(), created.providerReference(), "PROCESSING");
                repository.saveRefundResponse(intent.refundId(), saved);
                return new RefundResult(intent.refundId(), created.providerReference(), "PROCESSING", saved);
            }
            if (!repository.markRefundUnknown(intent.refundId(), created.providerReference(), intent.owner(), intent.token())) return null;
            byte[] saved = response(intent.refundId(), created.providerReference(), "UNKNOWN");
            repository.saveRefundResponse(intent.refundId(), saved);
            return new RefundResult(intent.refundId(), created.providerReference(), "UNKNOWN", saved);
        });
        return committed == null ? queryRefund(intent.refundId()) : committed;
    }

    private RefundResult finishUnknown(RefundIntent intent) {
        return finishUnknown(intent, null);
    }

    private RefundResult finishUnknown(RefundIntent intent, String reference) {
        RefundResult committed = transactions.execute(ignored -> {
            if (!repository.markRefundUnknown(intent.refundId(), reference, intent.owner(), intent.token())) return null;
            byte[] saved = response(intent.refundId(), reference, "UNKNOWN");
            repository.saveRefundResponse(intent.refundId(), saved);
            return new RefundResult(intent.refundId(), reference, "UNKNOWN", saved);
        });
        return committed == null ? queryRefund(intent.refundId()) : committed;
    }

    private byte[] settleTerminal(UUID refundId, UUID paymentId, long amountFen, String expectedReference,
                                   String reference, String status, String owner, String token) {
        if (!repository.markRefundTerminal(refundId, expectedReference, reference, status, amountFen, owner, token))
            return null;
        boolean settled = "SUCCEEDED".equals(status) ? repository.completeRefund(paymentId, amountFen) : repository.releaseRefund(paymentId, amountFen);
        if (!settled) throw new IllegalStateException("退款聚合结转失败，等待重试");
        byte[] saved = response(refundId, reference, status);
        repository.saveRefundResponse(refundId, saved);
        repository.insertPaymentEvent("REFUND_" + status, refundId, json(java.util.Map.of("refundId", refundId, "amountFen", amountFen)));
        return saved;
    }

    @Transactional
    public void handleCallback(PaymentGateway.VerifiedCallback callback, byte[] rawBody) {
        if (!"REFUND".equals(callback.type().name())) return;
        if (!repository.recordCallback(callback, rawBody)) return;
        JdbcPaymentRepository.RefundRecord refund = repository.findRefundByProviderReference(callback.provider(), callback.providerReference());
        if (refund == null) {
            if (!repository.failCallback(callback.provider(), callback.providerEventId())) throw new IllegalStateException("退款回调失败 CAS 失败，等待重试");
            return;
        }
        if (callback.amountFen() != refund.amountFen()) {
            if (!repository.failCallback(callback.provider(), callback.providerEventId())) throw new IllegalStateException("退款回调失败 CAS 失败，等待重试");
            return;
        }
        if ("SUCCEEDED".equals(callback.status())) {
            if (!repository.markRefundTerminalByCallback(callback.provider(), callback.providerReference(), callback.amountFen(), "SUCCEEDED"))
                throw new IllegalStateException("退款状态结算 CAS 失败，等待重试");
            repository.saveRefundResponse(refund.id(), response(refund.id(), callback.providerReference(), "SUCCEEDED"));
            if (!repository.completeRefund(refund.paymentId(), refund.amountFen())) throw new IllegalStateException("退款额度结转失败，等待重试");
            repository.insertPaymentEvent("REFUND_SUCCEEDED", refund.id(), json(java.util.Map.of("refundId", refund.id(), "orderId", refund.orderId(), "amountFen", refund.amountFen())));
        } else if ("FAILED".equals(callback.status())) {
            if (!repository.markRefundTerminalByCallback(callback.provider(), callback.providerReference(), callback.amountFen(), "FAILED"))
                throw new IllegalStateException("退款状态结算 CAS 失败，等待重试");
            repository.saveRefundResponse(refund.id(), response(refund.id(), callback.providerReference(), "FAILED"));
            if (!repository.releaseRefund(refund.paymentId(), refund.amountFen())) throw new IllegalStateException("退款额度释放失败，等待重试");
            repository.insertPaymentEvent("REFUND_FAILED", refund.id(), json(java.util.Map.of("refundId", refund.id(), "orderId", refund.orderId(), "amountFen", refund.amountFen())));
        }
        if (!repository.completeCallback(callback.provider(), callback.providerEventId()))
            throw new IllegalStateException("退款回调完成 CAS 失败，等待重试");
    }

    public record RefundResult(UUID refundId, String providerReference, String status, byte[] responseUtf8) {
        public RefundResult(UUID refundId, String providerReference, String status) { this(refundId, providerReference, status, null); }
    }
    private record RefundIntent(UUID refundId, UUID paymentId, String paymentReference, long amountFen,
                                String owner, String token, RefundResult existing) {}
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
