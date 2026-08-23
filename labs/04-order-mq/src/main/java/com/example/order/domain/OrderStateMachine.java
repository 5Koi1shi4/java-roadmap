package com.example.order.domain;

import static com.example.order.domain.OrderStatus.CANCELLED;
import static com.example.order.domain.OrderStatus.PAID;
import static com.example.order.domain.OrderStatus.PENDING_PAYMENT;

public class OrderStateMachine {
    public OrderStatus pay(OrderStatus status) {
        if (status != PENDING_PAYMENT) {
            throw new IllegalOrderTransitionException(status, PAID);
        }
        return PAID;
    }

    public OrderStatus cancelExpired(OrderStatus status) {
        return status == PENDING_PAYMENT ? CANCELLED : status;
    }
}
