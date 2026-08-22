package com.example.seckill.application;

public class AlreadyPurchasedException extends RuntimeException {
    public AlreadyPurchasedException(long userId, long productId) {
        super("用户 " + userId + " 已购买商品 " + productId);
    }
}
