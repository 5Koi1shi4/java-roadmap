package com.example.order.application;

import java.time.Instant;
import java.util.UUID;

/** Stable five-field payload sent through the timeout messaging pipeline. */
public record OrderTimeoutEvent(UUID eventId, String eventType, long orderId, Instant occurredAt, int version) {
    public OrderTimeoutEvent {
        if (eventId == null) {
            throw new InvalidOrderEventException("eventId must not be null");
        }
        if (!"ORDER_TIMEOUT".equals(eventType)) {
            throw new InvalidOrderEventException("eventType must be ORDER_TIMEOUT");
        }
        if (orderId <= 0) {
            throw new InvalidOrderEventException("orderId must be positive");
        }
        if (occurredAt == null) {
            throw new InvalidOrderEventException("occurredAt must not be null");
        }
        if (version != 1) {
            throw new InvalidOrderEventException("version must be 1");
        }
    }

    public OrderTimeoutEvent(String eventId, String eventType, long orderId, Instant occurredAt, int version) {
        this(parseEventId(eventId), eventType, orderId, occurredAt, version);
    }

    private static UUID parseEventId(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidOrderEventException("eventId must not be empty");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOrderEventException("eventId must be a UUID", exception);
        }
    }
}
