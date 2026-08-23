package com.example.order.infrastructure.mq;

import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Claims a bounded batch and only acknowledges rows after publisher confirm. */
@Component
public class OutboxDispatcher {
    private static final int MAX_BATCH_SIZE = 50;

    private final JdbcOrderRepository repository;
    private final OrderEventPublisher publisher;
    private final Clock clock;
    private final Duration leaseDuration;

    public OutboxDispatcher(JdbcOrderRepository repository, OrderEventPublisher publisher) {
        this(repository, publisher, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    public OutboxDispatcher(JdbcOrderRepository repository, OrderEventPublisher publisher,
                            Clock clock, Duration leaseDuration) {
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public void dispatchOnce() {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        List<OutboxEvent> events = repository.claimPublishable(now, leaseUntil, MAX_BATCH_SIZE);
        for (OutboxEvent event : events) {
            try {
                publisher.publish(event);
                repository.markPublished(event.eventId(), event.claimToken(), now);
            } catch (AmqpException exception) {
                repository.releaseForRetry(event.eventId(), event.claimToken(), "AMQP", message(exception));
            } catch (RuntimeException exception) {
                repository.releaseForRetry(event.eventId(), event.claimToken(), "PUBLISH", message(exception));
            }
        }
    }

    private String message(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
