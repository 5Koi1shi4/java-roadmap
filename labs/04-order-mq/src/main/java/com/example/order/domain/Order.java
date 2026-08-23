package com.example.order.domain;

public record Order(long id, long productId, int quantity, OrderStatus status) {
    public Order {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("productId must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }
}
