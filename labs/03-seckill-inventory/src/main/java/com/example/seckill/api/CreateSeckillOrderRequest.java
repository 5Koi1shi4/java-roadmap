package com.example.seckill.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateSeckillOrderRequest(
        @NotNull @Positive Long userId,
        @NotNull @Positive Long productId) {
}
