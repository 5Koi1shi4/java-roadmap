package com.example.campusmarket.unit.shared;

import com.example.campusmarket.shared.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventTest {
    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "o1", 1,
            Instant.now(), 2, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsVersionOneAndExposesStableSevenFields() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        DomainEvent event = new DomainEvent(eventId, "ORDER_CREATED", "o1", 1,
            occurredAt, 1, Map.of("orderId", "o1"));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.eventType()).isEqualTo("ORDER_CREATED");
        assertThat(event.aggregateId()).isEqualTo("o1");
        assertThat(event.aggregateVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.payload()).containsEntry("orderId", "o1");
    }

    @Test
    void rejectsInvalidRequiredValues() {
        assertThatThrownBy(() -> new DomainEvent(null, "ORDER_CREATED", "o1", 1,
            Instant.now(), 1, Map.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "", "o1", 1,
            Instant.now(), 1, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "", 1,
            Instant.now(), 1, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "o1", 0,
            Instant.now(), 1, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "o1", 1,
            null, 1, Map.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "o1", 1,
            Instant.now(), 1, null))
            .isInstanceOf(NullPointerException.class);
    }
}
