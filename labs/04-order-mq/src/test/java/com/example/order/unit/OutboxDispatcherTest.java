package com.example.order.unit;

import com.example.order.infrastructure.mq.OrderEventPublisher;
import com.example.order.infrastructure.mq.OutboxDispatcher;
import com.example.order.infrastructure.mq.OutboxEvent;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void dispatchesAtMostFiftyOldestClaimedEventsAndMarksEachAfterConfirm() {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        OrderEventPublisher publisher = mock(OrderEventPublisher.class);
        OutboxEvent first = event();
        OutboxEvent second = event();
        when(repository.claimPublishable(NOW, NOW.plusSeconds(30), 50))
                .thenReturn(List.of(first, second));

        new OutboxDispatcher(repository, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).dispatchOnce();

        verify(repository).claimPublishable(NOW, NOW.plusSeconds(30), 50);
        verify(publisher).publish(first);
        verify(repository).markPublished(first.eventId(), NOW);
        verify(publisher).publish(second);
        verify(repository).markPublished(second.eventId(), NOW);
        verifyNoMoreInteractions(repository, publisher);
    }

    @Test
    void releasesEventWhenPublisherNacks() {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        OrderEventPublisher publisher = mock(OrderEventPublisher.class);
        OutboxEvent event = event();
        when(repository.claimPublishable(NOW, NOW.plusSeconds(30), 50)).thenReturn(List.of(event));
        doThrow(new AmqpException("publisher nack")).when(publisher).publish(event);

        new OutboxDispatcher(repository, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).dispatchOnce();

        verify(repository).releaseForRetry(eq(event.eventId()), eq("AMQP"), eq("publisher nack"));
    }

    @Test
    void releasesEventWhenPublisherThrowsUnexpectedFailure() {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        OrderEventPublisher publisher = mock(OrderEventPublisher.class);
        OutboxEvent event = event();
        when(repository.claimPublishable(NOW, NOW.plusSeconds(30), 50)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("connection closed")).when(publisher).publish(event);

        new OutboxDispatcher(repository, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).dispatchOnce();

        verify(repository).releaseForRetry(eq(event.eventId()), eq("PUBLISH"), eq("connection closed"));
    }

    @Test
    void publisherTreatsNegativeConfirmAsAmqpFailure() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(rabbitTemplate.invoke(org.mockito.ArgumentMatchers
                .<RabbitOperations.OperationsCallback<Boolean>>any())).thenReturn(false);

        assertThatThrownBy(() -> new OrderEventPublisher(rabbitTemplate).publish(event()))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("publisher confirm nack");
    }

    @Test
    void publisherPropagatesAmqpExceptionFromTemplate() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(rabbitTemplate.invoke(org.mockito.ArgumentMatchers
                .<RabbitOperations.OperationsCallback<Boolean>>any()))
                .thenThrow(new AmqpException("broker unavailable"));

        assertThatThrownBy(() -> new OrderEventPublisher(rabbitTemplate).publish(event()))
                .isInstanceOf(AmqpException.class)
                .hasMessage("broker unavailable");
    }

    private OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), "ORDER", 1L, "ORDER_TIMEOUT", "{}", NOW);
    }
}
