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
}
