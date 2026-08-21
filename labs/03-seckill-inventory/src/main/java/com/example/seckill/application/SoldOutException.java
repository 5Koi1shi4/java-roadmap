package com.example.seckill.application;

public class SoldOutException extends RuntimeException {
    public SoldOutException(long productId) {
        super("Product is sold out: " + productId);
    }
}
