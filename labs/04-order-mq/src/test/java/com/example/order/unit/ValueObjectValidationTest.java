package com.example.order.unit;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.infrastructure.mq.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ValueObjectValidationTest {
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void orderRejectsNonPositiveId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Order(0, 1, 1, OrderStatus.PENDING_PAYMENT));
    }

    @Test
    void orderRejectsNonPositiveProductId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Order(1, 0, 1, OrderStatus.PENDING_PAYMENT));
    }

    @Test
    void orderRejectsNonPositiveQuantity() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Order(1, 1, 0, OrderStatus.PENDING_PAYMENT));
    }

    @Test
    void orderRejectsNullStatus() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Order(1, 1, 1, null));
    }

    @Test
    void outboxEventRejectsNullEventId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(null, CLAIM_TOKEN, "ORDER", 1, "ORDER_TIMEOUT", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsNullClaimToken() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, null, "ORDER", 1, "ORDER_TIMEOUT", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsWrongAggregateType() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, "PAYMENT", 1, "ORDER_TIMEOUT", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsBlankAggregateType() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, " ", 1, "ORDER_TIMEOUT", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsNonPositiveAggregateId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, "ORDER", 0, "ORDER_TIMEOUT", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsWrongEventType() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, "ORDER", 1, "ORDER_CREATED", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsBlankEventType() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, "ORDER", 1, "\t", "{}", CREATED_AT));
    }

    @Test
    void outboxEventRejectsBlankPayload() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, "ORDER", 1, "ORDER_TIMEOUT", " \t", CREATED_AT));
    }

    @Test
    void outboxEventRejectsNullCreatedAt() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OutboxEvent(EVENT_ID, CLAIM_TOKEN, "ORDER", 1, "ORDER_TIMEOUT", "{}", null));
    }
}
