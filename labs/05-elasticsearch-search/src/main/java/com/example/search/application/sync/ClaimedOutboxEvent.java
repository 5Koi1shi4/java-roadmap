package com.example.search.application.sync;

import com.example.search.domain.ProductSearchSnapshot;

import java.time.Instant;
import java.util.UUID;

public record ClaimedOutboxEvent(long id, UUID eventId, long productId, long productVersion,
                                 OutboxEventType eventType, ProductSearchSnapshot snapshot,
                                 int attemptCount, String owner, UUID claimToken, Instant leaseUntil) {
}
