package com.example.order.unit;

import com.example.order.domain.OrderStateMachine;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.IllegalOrderTransitionException;
import org.junit.jupiter.api.Test;

import static com.example.order.domain.OrderStatus.CANCELLED;
import static com.example.order.domain.OrderStatus.PAID;
import static com.example.order.domain.OrderStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {
    private final OrderStateMachine machine = new OrderStateMachine();

    @Test
    void paysOnlyPendingOrder() {
        assertThat(machine.pay(PENDING_PAYMENT)).isEqualTo(PAID);
        assertThatThrownBy(() -> machine.pay(CANCELLED))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void timeoutCancelsPendingAndIsIdempotent() {
        assertThat(machine.cancelExpired(PENDING_PAYMENT)).isEqualTo(CANCELLED);
        assertThat(machine.cancelExpired(CANCELLED)).isEqualTo(CANCELLED);
        assertThat(machine.cancelExpired(PAID)).isEqualTo(PAID);
    }
}
