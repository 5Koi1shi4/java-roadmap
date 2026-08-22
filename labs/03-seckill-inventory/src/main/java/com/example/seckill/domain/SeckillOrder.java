package com.example.seckill.domain;

import java.time.Instant;

public record SeckillOrder(long id, long userId, long productId, Instant createdAt) {
}
