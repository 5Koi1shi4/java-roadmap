package com.example.order.domain;

public class IllegalOrderTransitionException extends IllegalStateException {
    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("Illegal order transition from " + from + " to " + to);
    }
}
