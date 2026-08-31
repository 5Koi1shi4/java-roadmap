package com.example.campusmarket.unit.messaging;

import com.example.campusmarket.messaging.EventEnvelopeCodec;
import com.example.campusmarket.shared.DomainEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeCodecTest {
    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

    @Test
    void roundTripsDomainEventAsExplicitUtf8Json() {
        UUID id = UUID.randomUUID();
        DomainEvent event = new DomainEvent(id, "ORDER_CREATED", "order-1", 2,
            Instant.parse("2026-08-30T01:02:03Z"), 1, Map.of("orderId", "order-1", "quantity", 2));

        byte[] encoded = codec.encode(event);

        assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("\"eventId\"");
        assertThat(codec.decode(encoded)).isEqualTo(event);
    }

    @Test
    void rejectsUnknownFields() {
        String json = validJson().replace("}", ",\"unknown\":true}");

        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRequiredFields() {
        String json = validJson().replace("\"eventType\":\"ORDER_CREATED\",", "");

        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String json = validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedUuid() {
        String json = validJson().replace("00000000-0000-0000-0000-000000000001", "not-a-uuid");

        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveAggregateVersion() {
        String json = validJson().replace("\"aggregateVersion\":1", "\"aggregateVersion\":0");

        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullJson() {
        assertThatThrownBy(() -> codec.decode((byte[]) null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownEventTypeAtProtocolBoundary() {
        String json = validJson().replace("ORDER_CREATED", "NOT_A_REAL_EVENT");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(IllegalArgumentException.class);
    }

    private String validJson() {
        return "{\"eventId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"eventType\":\"ORDER_CREATED\",\"aggregateId\":\"order-1\","
            + "\"aggregateVersion\":1,\"occurredAt\":\"2026-08-30T01:02:03Z\","
            + "\"schemaVersion\":1,\"payload\":{\"orderId\":\"order-1\"}}";
    }
}
