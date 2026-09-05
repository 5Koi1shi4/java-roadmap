package com.example.campusmarket.unit.payment;

import com.example.campusmarket.payment.application.PaymentReconciliationScheduler;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentReconciliationSchedulerTest {
    @Test
    void runOneReportsRetryPendingWhenReconciliationFailsAfterClaim() {
        JdbcPaymentRepository repository = mock(JdbcPaymentRepository.class);
        PaymentService payments = mock(PaymentService.class);
        RefundService refunds = mock(RefundService.class);
        UUID paymentId = UUID.randomUUID();
        when(repository.claimPaymentReconciliation(eq(paymentId), eq("payment-reconciler"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true);
        doThrow(new IllegalStateException("provider unavailable"))
            .when(payments).reconcilePayment(eq(paymentId), eq("payment-reconciler"), org.mockito.ArgumentMatchers.anyString());

        assertThat(new PaymentReconciliationScheduler(repository, payments, refunds).runOne(paymentId))
            .isEqualTo(PaymentReconciliationScheduler.ReconciliationResult.RETRY_PENDING);
    }
}
