package com.example.campusmarket.shared;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stable seven-field contract used to publish an aggregate event. */
public record DomainEvent(
    UUID eventId,
    String eventType,
    String aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    int schemaVersion,
    Map<String, Object> payload
) {
    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        requireNonBlank(eventType, "eventType 不能为空");
        requireNonBlank(aggregateId, "aggregateId 不能为空");
        if (aggregateVersion <= 0) {
            throw new IllegalArgumentException("aggregateVersion 必须为正数");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("仅支持 schemaVersion=1");
        }
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload 不能为空"));
    }

    private static void requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
