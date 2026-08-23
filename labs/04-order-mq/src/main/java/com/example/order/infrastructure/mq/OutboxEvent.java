package com.example.order.infrastructure.mq;

import java.time.Instant;
import java.util.UUID;

/** An outbox row claimed by a dispatcher and ready for publication. */
public record OutboxEvent(
        UUID eventId,
        UUID claimToken,
        String aggregateType,
        long aggregateId,
        String eventType,
        String payload,
        Instant createdAt) {
    public OutboxEvent {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        if (claimToken == null) {
            throw new IllegalArgumentException("claimToken must not be null");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        if (!"ORDER".equals(aggregateType)) {
            throw new IllegalArgumentException("aggregateType must be ORDER");
        }
        if (aggregateId <= 0) {
            throw new IllegalArgumentException("aggregateId must be positive");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (!"ORDER_TIMEOUT".equals(eventType)) {
            throw new IllegalArgumentException("eventType must be ORDER_TIMEOUT");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
