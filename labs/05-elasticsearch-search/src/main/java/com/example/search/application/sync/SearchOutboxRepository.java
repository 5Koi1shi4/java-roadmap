package com.example.search.application.sync;

import com.example.search.domain.ProductSearchSnapshot;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface SearchOutboxRepository {
    void append(ProductSearchSnapshot snapshot, OutboxEventType eventType);

    default List<ClaimedOutboxEvent> claim(String owner, int limit) {
        throw new UnsupportedOperationException("outbox claiming is not implemented");
    }

    default boolean complete(UUID eventId, UUID token) {
        throw new UnsupportedOperationException("outbox completion is not implemented");
    }

    default boolean reschedule(UUID eventId, UUID token, Duration delay, String reason) {
        throw new UnsupportedOperationException("outbox rescheduling is not implemented");
    }

    default boolean fail(UUID eventId, UUID token, String reason) {
        throw new UnsupportedOperationException("outbox failure is not implemented");
    }
}
