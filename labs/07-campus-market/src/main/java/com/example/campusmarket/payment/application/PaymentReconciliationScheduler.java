package com.example.campusmarket.payment.application;

import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** 以数据库时间和短租约领取 UNKNOWN，按原 reference/幂等键查询，不重新创建请求。 */
@Component
@Profile("!test")
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.payment.reconciliation", name = "enabled", havingValue = "true")
public final class PaymentReconciliationScheduler {
    private final JdbcPaymentRepository repository;
    private final PaymentService payments;
    private final RefundService refunds;

    public PaymentReconciliationScheduler(JdbcPaymentRepository repository, PaymentService payments, RefundService refunds) {
        this.repository = repository;
        this.payments = payments;
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${campus.market.payment.reconciliation.fixed-delay-ms:1000}")
    public void dispatch() {
        runOnce(50);
    }

    public int runOnce(int batchSize) {
        int processed = 0;
        for (var id : repository.duePaymentReconciliations(batchSize)) {
            String token = UUID.randomUUID().toString();
            if (repository.claimPaymentReconciliation(id, "payment-reconciler", token)) {
                try { payments.reconcilePayment(id, "payment-reconciler", token); } catch (RuntimeException ignored) { }
                processed++;
            }
        }
        for (var id : repository.dueRefundReconciliations(batchSize)) {
            String token = UUID.randomUUID().toString();
            if (repository.claimRefundReconciliation(id, "refund-reconciler", token)) {
                try { refunds.reconcileRefund(id, "refund-reconciler", token); } catch (RuntimeException ignored) { }
                processed++;
            }
        }
        return processed;
    }
}
