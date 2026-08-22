package com.example.seckill.api;

import com.example.seckill.domain.SeckillOrder;

import java.time.Instant;

public record SeckillOrderResponse(long id, long userId, long productId, Instant createdAt) {
    public static SeckillOrderResponse from(SeckillOrder order) {
        return new SeckillOrderResponse(order.id(), order.userId(), order.productId(), order.createdAt());
    }
}
