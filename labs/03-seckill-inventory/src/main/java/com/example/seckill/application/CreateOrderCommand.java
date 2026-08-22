package com.example.seckill.application;

/** 已完成校验的下单请求及其规范化完整请求体。 */
public record CreateOrderCommand(long userId, long productId, String normalizedRequestBody) {
    public CreateOrderCommand(long userId, long productId) {
        this(userId, productId, "{\"userId\":" + userId + ",\"productId\":" + productId + "}");
    }
}
