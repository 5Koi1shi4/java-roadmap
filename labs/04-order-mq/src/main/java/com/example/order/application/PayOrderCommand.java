package com.example.order.application;

public record PayOrderCommand(long orderId) {
    public PayOrderCommand {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }
}
