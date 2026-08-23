package com.example.order.application;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(long productId, int quantity) {
        super("Insufficient stock for product " + productId + " (quantity " + quantity + ")");
    }
}
