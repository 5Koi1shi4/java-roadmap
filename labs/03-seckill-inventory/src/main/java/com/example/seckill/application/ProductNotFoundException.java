package com.example.seckill.application;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(long productId) {
        super("Product not found: " + productId);
    }
}
