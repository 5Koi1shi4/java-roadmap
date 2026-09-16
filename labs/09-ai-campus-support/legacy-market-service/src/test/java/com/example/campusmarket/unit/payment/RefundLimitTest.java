package com.example.campusmarket.unit.payment;

import com.example.campusmarket.payment.domain.PaymentAggregate;
import com.example.campusmarket.shared.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundLimitTest {
    @Test
    void rejectsRefundCreatedWithInvalidReferenceStatusOrAmount() {
        assertThatThrownBy(() -> new com.example.campusmarket.payment.application.PaymentGateway.RefundCreated(
            null, com.example.campusmarket.payment.application.PaymentGateway.RefundStatus.Status.SUCCEEDED, 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.example.campusmarket.payment.application.PaymentGateway.RefundCreated(
            " ", com.example.campusmarket.payment.application.PaymentGateway.RefundStatus.Status.SUCCEEDED, 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.example.campusmarket.payment.application.PaymentGateway.RefundCreated(
            "provider-ref", null, 1L))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new com.example.campusmarket.payment.application.PaymentGateway.RefundCreated(
            "provider-ref", com.example.campusmarket.payment.application.PaymentGateway.RefundStatus.Status.SUCCEEDED, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.example.campusmarket.payment.application.PaymentGateway.RefundCreated(
            "provider-ref", com.example.campusmarket.payment.application.PaymentGateway.RefundStatus.Status.SUCCEEDED, -1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservesConcurrentPartialRefundsWithoutExceedingPaidAmount() {
        PaymentAggregate payment = PaymentAggregate.paid(Money.ofFen(10_000));

        assertThat(payment.reserveRefund("r1", Money.ofFen(6_000))).isTrue();
        assertThat(payment.reserveRefund("r2", Money.ofFen(5_000))).isFalse();
    }
}
