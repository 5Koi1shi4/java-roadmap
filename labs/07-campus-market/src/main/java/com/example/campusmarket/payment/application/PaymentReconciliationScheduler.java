package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** 以数据库时间和短租约领取 UNKNOWN，按原 reference/幂等键查询，不重新创建请求。 */
@Component
@Profile("!test")
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.payment.reconciliation", name = "enabled", havingValue = "true")
public final class PaymentReconciliationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);
    private final JdbcPaymentRepository repository;
    private final PaymentService payments;
    private final RefundService refunds;

    public PaymentReconciliationScheduler(JdbcPaymentRepository repository, PaymentService payments, RefundService refunds) {
        this.repository = repository;
        this.payments = payments;
        this.refunds = refunds;
    }

    @Scheduled(initialDelayString = "${campus.market.payment.reconciliation.initial-delay-ms:0}",
        fixedDelayString = "${campus.market.payment.reconciliation.fixed-delay-ms:1000}")
    public void dispatch() {
        runOnce(50);
    }

    public int runOnce(int batchSize) {
        int processed = 0;
        for (var id : repository.duePaymentReconciliations(batchSize)) {
            String token = UUID.randomUUID().toString();
            if (repository.claimPaymentReconciliation(id, "payment-reconciler", token)) {
                try { payments.reconcilePayment(id, "payment-reconciler", token); } catch (RuntimeException failure) {
                    LOG.warn("支付对账失败，保留 UNKNOWN 供下次重试 paymentId={} failureClass={}", id, failure.getClass().getSimpleName());
                }
                processed++;
            }
        }
        for (var id : repository.dueRefundReconciliations(batchSize)) {
            String token = UUID.randomUUID().toString();
            if (repository.claimRefundReconciliation(id, "refund-reconciler", token)) {
                try { refunds.reconcileRefund(id, "refund-reconciler", token); } catch (RuntimeException failure) {
                    LOG.warn("退款对账失败，保留状态供下次重试 refundId={} failureClass={}", id, failure.getClass().getSimpleName());
                }
                processed++;
            }
        }
        return processed;
    }

    /** 定向对账结果；调用方必须能区分未领取、已完成和等待租约重试。 */
    public ReconciliationResult runOne(UUID paymentId) {
        String token = UUID.randomUUID().toString();
        if (!repository.claimPaymentReconciliation(paymentId, "payment-reconciler", token)) return ReconciliationResult.NOT_CLAIMED;
        try {
            PaymentService.PaymentResult result = payments.reconcilePayment(paymentId, "payment-reconciler", token);
            if (result == null || "PENDING".equals(result.status()) || "UNKNOWN".equals(result.status()))
                return ReconciliationResult.RETRY_PENDING;
            return ReconciliationResult.PROCESSED;
        } catch (RuntimeException failure) {
            LOG.warn("指定支付对账失败 paymentId={} failureClass={}", paymentId, failure.getClass().getSimpleName());
            return ReconciliationResult.RETRY_PENDING;
        }
    }

    public enum ReconciliationResult { NOT_CLAIMED, PROCESSED, RETRY_PENDING }
}
