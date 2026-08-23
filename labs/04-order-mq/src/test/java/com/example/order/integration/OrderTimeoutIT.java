package com.example.order.integration;

import com.example.order.application.OrderService;
import com.example.order.application.OrderTimeoutEvent;
import com.example.order.infrastructure.mq.OrderTimeoutConsumer;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class OrderTimeoutIT {
    @Test
    void duplicateCompletedEventDoesNotReleaseStockAgain() {
        OrderTimeoutEvent event = new OrderTimeoutEvent(
                UUID.randomUUID(), "ORDER_TIMEOUT", 1, Instant.parse("2026-08-23T08:00:00Z"), 1);
        JdbcOrderRepository repository = Mockito.mock(JdbcOrderRepository.class);
        OrderService service = Mockito.mock(OrderService.class);
        UUID claimToken = UUID.randomUUID();
        Mockito.when(repository.beginConsumption(Mockito.eq(event.eventId()), Mockito.any(), Mockito.any()))
                .thenReturn(new JdbcOrderRepository.ConsumptionClaim(
                        JdbcOrderRepository.ConsumptionClaim.State.PROCESSING, claimToken))
                .thenReturn(new JdbcOrderRepository.ConsumptionClaim(
                        JdbcOrderRepository.ConsumptionClaim.State.COMPLETED, null));
        Mockito.when(repository.completeConsumption(Mockito.eq(event.eventId()), Mockito.eq(claimToken), Mockito.any()))
                .thenReturn(1);

        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(repository, service);
        consumer.handle(event);
        consumer.handle(event);

        assertThat(event.schemaVersion()).isEqualTo(1);
        verify(service).cancelExpired(event);
        verify(service, Mockito.times(1)).cancelExpired(event);
    }
}
