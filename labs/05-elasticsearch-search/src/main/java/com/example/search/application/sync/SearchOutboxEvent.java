package com.example.search.application.sync;

import com.example.search.domain.ProductSearchSnapshot;

import java.util.UUID;

public record SearchOutboxEvent(long id, UUID eventId, long productId, long productVersion,
                                OutboxEventType eventType, ProductSearchSnapshot snapshot,
                                int attemptCount) {
}
