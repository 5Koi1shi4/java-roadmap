package com.example.order.unit;

import com.example.order.infrastructure.mq.ManualFailureDispatcher;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualFailureDispatcherTest {
    @Test
    void failedPublishRemainsPendingUntilLimitedAttemptsThenBecomesGiveUp() {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        ManualFailureDispatcher dispatcher = new ManualFailureDispatcher(repository, template, 3);
        when(repository.pendingManualFailures(50)).thenReturn(List.of(failure(1L, 0)));
        when(repository.markManualAttempt(any(Long.class), any(Instant.class))).thenReturn(failure(1L, 1));
        when(template.invoke(any())).thenReturn(false);

        dispatcher.dispatchOnce();

        verify(repository, never()).markManualGiveUp(eq(1L), any(Instant.class));
    }

    @Test
    void failedPublishAtLimitBecomesGiveUp() {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        ManualFailureDispatcher dispatcher = new ManualFailureDispatcher(repository, template, 3);
        when(repository.pendingManualFailures(50)).thenReturn(List.of(failure(1L, 2)));
        when(repository.markManualAttempt(any(Long.class), any(Instant.class))).thenReturn(failure(1L, 3));
        when(template.invoke(any())).thenReturn(false);

        dispatcher.dispatchOnce();

        verify(repository).markManualGiveUp(eq(1L), any(Instant.class));
    }

    @Test
    void confirmedPublishBecomesDelivered() {
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        ManualFailureDispatcher dispatcher = new ManualFailureDispatcher(repository, template, 3);
        when(repository.pendingManualFailures(50)).thenReturn(List.of(failure(1L, 0)));
        when(repository.markManualAttempt(any(Long.class), any(Instant.class))).thenReturn(failure(1L, 1));
        when(template.invoke(any())).thenReturn(true);

        dispatcher.dispatchOnce();

        verify(repository).markManualDelivered(eq(1L), any(Instant.class));
    }

    @Test
    void pendingFailureRetainsReplayablePayload() {
        JdbcOrderRepository.ManualFailure failure = failure(1L, 0);

        assertThat(failure.payload()).isEqualTo("{broken-json");
        assertThat(failure.status()).isEqualTo(JdbcOrderRepository.ManualDeliveryStatus.PENDING);
    }

    private JdbcOrderRepository.ManualFailure failure(long id, int attempts) {
        return new JdbcOrderRepository.ManualFailure(
                id, UUID.randomUUID(), "{broken-json", "NON_RETRYABLE", "bad", 1, attempts,
                JdbcOrderRepository.ManualDeliveryStatus.PENDING, Instant.now());
    }
}
