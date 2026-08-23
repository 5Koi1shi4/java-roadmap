package com.example.order.unit;

import com.example.order.application.OrderService;
import com.example.order.application.OrderTimeoutEvent;
import com.example.order.infrastructure.mq.NonRetryableMessageException;
import com.example.order.infrastructure.mq.OrderTimeoutConsumer;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class OrderTimeoutConsumerTest {
    @Test
    void exhaustedInboundRetryCountGoesToRecovererWithoutBusinessExecution() throws Exception {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        OrderService service = mock(OrderService.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(repository, service);
        OrderTimeoutEvent event = new OrderTimeoutEvent(
                UUID.randomUUID(), "ORDER_TIMEOUT", 1L, Instant.parse("2026-08-23T08:00:00Z"), 1);
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-retry-count", 3);
        Message message = new Message(mapper.writeValueAsBytes(event), properties);

        assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessageContaining("exceeds maximum");
        verifyNoInteractions(repository, service);
    }

    @Test
    void nonCanonicalRetryHeaderDoesNotConsumeRetryBudget() throws Exception {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        OrderService service = mock(OrderService.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(repository, service);
        OrderTimeoutEvent event = new OrderTimeoutEvent(
                UUID.randomUUID(), "ORDER_TIMEOUT", 1L, Instant.parse("2026-08-23T08:00:00Z"), 1);
        UUID token = UUID.randomUUID();
        when(repository.beginConsumption(eq(event.eventId()), any(), any()))
                .thenReturn(new JdbcOrderRepository.ConsumptionClaim(
                        JdbcOrderRepository.ConsumptionClaim.State.PROCESSING, token));
        when(repository.completeConsumption(eq(event.eventId()), eq(token), any())).thenReturn(1);
        MessageProperties properties = new MessageProperties();
        properties.setHeader("retry-count", 3);
        Message message = new Message(mapper.writeValueAsBytes(event), properties);

        consumer.consume(message);

        org.mockito.Mockito.verify(service).cancelExpired(event);
    }
}
