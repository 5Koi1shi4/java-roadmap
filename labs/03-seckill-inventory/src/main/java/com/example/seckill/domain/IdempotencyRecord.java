package com.example.seckill.domain;

import java.time.Instant;

public record IdempotencyRecord(
        String idempotencyKey,
        String requestHash,
        String status,
        Integer responseStatus,
        String responseBody,
        Instant createdAt,
        Instant updatedAt) {
}
