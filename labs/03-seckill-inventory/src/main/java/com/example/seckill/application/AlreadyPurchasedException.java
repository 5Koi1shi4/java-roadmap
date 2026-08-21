package com.example.seckill.application;

public class AlreadyPurchasedException extends RuntimeException {
    public AlreadyPurchasedException(long userId, long productId) {
        super("User " + userId + " already purchased product " + productId);
    }
}
