package com.example.campusmarket.unit.payment;

import com.example.campusmarket.payment.application.PaymentReconciliationScheduler;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.observability.CampusMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    @Test
    void successfulReconciliationDoesNotRecordRetry() {
        JdbcPaymentRepository repository = mock(JdbcPaymentRepository.class);
        PaymentService payments = mock(PaymentService.class);
        RefundService refunds = mock(RefundService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);
        UUID paymentId = UUID.randomUUID();
        when(repository.duePaymentReconciliations(10)).thenReturn(java.util.List.of(paymentId));
        when(repository.dueRefundReconciliations(10)).thenReturn(java.util.List.of());
        when(repository.claimPaymentReconciliation(eq(paymentId), eq("payment-reconciler"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true);
        when(payments.reconcilePayment(eq(paymentId), eq("payment-reconciler"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new PaymentService.PaymentResult(paymentId, "provider-ref", "SUCCEEDED", new byte[0]));

        new PaymentReconciliationScheduler(repository, payments, refunds, metrics).runOnce(10);

        assertThat(registry.find("campus.market.retry.total.PAYMENT").counter()).isNull();
    }

    @Test
    void successfulRefundReconciliationDoesNotRecordRetry() {
        JdbcPaymentRepository repository = mock(JdbcPaymentRepository.class);
        PaymentService payments = mock(PaymentService.class);
        RefundService refunds = mock(RefundService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);
        UUID refundId = UUID.randomUUID();
        when(repository.duePaymentReconciliations(10)).thenReturn(java.util.List.of());
        when(repository.dueRefundReconciliations(10)).thenReturn(java.util.List.of(refundId));
        when(repository.claimRefundReconciliation(eq(refundId), eq("refund-reconciler"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true);
        when(refunds.reconcileRefund(eq(refundId), eq("refund-reconciler"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new RefundService.RefundResult(refundId, "provider-ref", "SUCCEEDED", new byte[0]));

        new PaymentReconciliationScheduler(repository, payments, refunds, metrics).runOnce(10);

        assertThat(registry.find("campus.market.retry.total.REFUND").counter()).isNull();
    }
}
