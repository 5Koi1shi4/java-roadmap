package com.example.order.infrastructure.mq;

import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Bounded compensating publisher for durable manual-failure records. */
@Component
public class ManualFailureDispatcher {
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcOrderRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;
    private final int maxAttempts;

    @Autowired
    public ManualFailureDispatcher(JdbcOrderRepository repository, RabbitTemplate rabbitTemplate) {
        this(repository, rabbitTemplate, MAX_ATTEMPTS);
    }

    public ManualFailureDispatcher(JdbcOrderRepository repository, RabbitTemplate rabbitTemplate, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = Clock.systemUTC();
        this.maxAttempts = maxAttempts;
    }

    public void dispatchOnce() {
        List<JdbcOrderRepository.ManualFailure> pending = repository.pendingManualFailures(BATCH_SIZE);
        for (JdbcOrderRepository.ManualFailure original : pending) {
            JdbcOrderRepository.ManualFailure failure = repository.markManualAttempt(original.id(), clock.instant());
            if (failure == null || failure.status() != JdbcOrderRepository.ManualDeliveryStatus.PENDING) {
                continue;
            }
            try {
                Boolean confirmed = rabbitTemplate.invoke(operations -> {
                    operations.send("", RabbitTopologyConfiguration.MANUAL_QUEUE, message(failure));
                    return operations.waitForConfirms(10_000L);
                });
                if (Boolean.TRUE.equals(confirmed)) {
                    repository.markManualDelivered(failure.id(), clock.instant());
                } else {
                    markFailure(failure);
                }
            } catch (RuntimeException exception) {
                markFailure(failure);
            }
        }
    }

    private void markFailure(JdbcOrderRepository.ManualFailure failure) {
        if (failure.attempts() >= maxAttempts) {
            repository.markManualGiveUp(failure.id(), clock.instant());
        }
    }

    private Message message(JdbcOrderRepository.ManualFailure failure) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("failure-category", failure.category());
        properties.setHeader("failure-message", failure.message());
        properties.setHeader("x-retry-count", failure.retryCount());
        if (failure.eventId() != null) {
            properties.setHeader("event-id", failure.eventId().toString());
        }
        return new Message(failure.payload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
