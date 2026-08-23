package com.example.order.application;

public record CreateOrderCommand(long productId, int quantity) {
    public CreateOrderCommand {
        if (productId <= 0) {
            throw new IllegalArgumentException("productId must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
