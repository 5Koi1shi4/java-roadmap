package com.example.campusmarket.unit.payment;

import com.example.campusmarket.payment.domain.PaymentAggregate;
import com.example.campusmarket.shared.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundLimitTest {
    @Test
    void reservesConcurrentPartialRefundsWithoutExceedingPaidAmount() {
        PaymentAggregate payment = PaymentAggregate.paid(Money.ofFen(10_000));

        assertThat(payment.reserveRefund("r1", Money.ofFen(6_000))).isTrue();
        assertThat(payment.reserveRefund("r2", Money.ofFen(5_000))).isFalse();
    }
}
