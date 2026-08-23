package com.example.order.application;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Stable five-field payload sent through the timeout messaging pipeline. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record OrderTimeoutEvent(UUID eventId, String eventType, long orderId, Instant occurredAt, int schemaVersion) {
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
        if (schemaVersion != 1) {
            throw new InvalidOrderEventException("schemaVersion must be 1");
        }
    }

    public OrderTimeoutEvent(String eventId, String eventType, long orderId, Instant occurredAt, int schemaVersion) {
        this(parseEventId(eventId), eventType, orderId, occurredAt, schemaVersion);
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
