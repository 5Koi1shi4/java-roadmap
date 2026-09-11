package com.example.campusmarket.unit.messaging;

import com.example.campusmarket.messaging.OrderEventBusinessHandler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

class OrderEventBusinessHandlerTest {
    @Test
    void ownsOrderPaidAndHandoffEventsForProductionRouting() {
        OrderEventBusinessHandler handler = new OrderEventBusinessHandler(mock(com.example.campusmarket.order.application.OrderLifecycleService.class));

        assertThat(handler.supports("ORDER_PAID")).isTrue();
        assertThat(handler.supports("WARRANTY_REFUND_REQUESTED")).isFalse();
    }

    @Test
    void delegatesPaymentEventToOrderLifecycleUseCase() {
        var lifecycle = mock(com.example.campusmarket.order.application.OrderLifecycleService.class);
        UUID orderId = UUID.randomUUID();
        when(lifecycle.applyPaymentSucceeded(orderId)).thenReturn(true);
        OrderEventBusinessHandler handler = new OrderEventBusinessHandler(lifecycle);

        handler.handle(new com.example.campusmarket.shared.DomainEvent(
            UUID.randomUUID(), "ORDER_PAID", orderId.toString(), 1, Instant.now(), 1,
            Map.of("orderId", orderId.toString())));

        verify(lifecycle).applyPaymentSucceeded(orderId);
    }
}
