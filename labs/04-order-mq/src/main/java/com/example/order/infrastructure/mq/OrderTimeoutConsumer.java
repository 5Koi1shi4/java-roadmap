package com.example.order.infrastructure.mq;

import com.example.order.application.OrderService;
import com.example.order.application.OrderTimeoutEvent;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Idempotent timeout consumer with a fenced database lease. */
@Component
public class OrderTimeoutConsumer {
    private final JdbcOrderRepository repository;
    private final OrderService orderService;
    private final FailureClassifier failureClassifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration leaseDuration;

    public OrderTimeoutConsumer(JdbcOrderRepository repository, OrderService orderService,
                                FailureClassifier failureClassifier, ObjectMapper objectMapper) {
        this(repository, orderService, failureClassifier, objectMapper, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    public OrderTimeoutConsumer(JdbcOrderRepository repository, OrderService orderService) {
        this(repository, orderService, new FailureClassifier(),
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    public OrderTimeoutConsumer(JdbcOrderRepository repository, OrderService orderService,
                                FailureClassifier failureClassifier, ObjectMapper objectMapper,
                                Clock clock, Duration leaseDuration) {
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.repository = repository;
        this.orderService = orderService;
        this.failureClassifier = failureClassifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    /** Converts the wire payload at the message boundary; malformed JSON is permanent. */
    @RabbitListener(queues = RabbitTopologyConfiguration.TIMEOUT_QUEUE_1M)
    @Transactional
    public void consume(Message message) {
        try {
            OrderTimeoutEvent event = objectMapper.readValue(message.getBody(), OrderTimeoutEvent.class);
            int retryCount = retryCount(message);
            if (retryCount > 3) {
                throw new NonRetryableMessageException("retry count exceeds maximum of 3");
            }
            handle(event);
        } catch (NonRetryableMessageException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new NonRetryableMessageException("invalid order timeout message", exception);
        }
    }

    /** Handles one valid event. Database claim, business action and completion share one transaction. */
    @Transactional
    public void handle(OrderTimeoutEvent event) {
        Instant now = clock.instant();
        JdbcOrderRepository.ConsumptionClaim claim;
        try {
            claim = repository.beginConsumption(event.eventId(), now, now.plus(leaseDuration));
        } catch (RuntimeException exception) {
            throw classified(exception);
        }
        if (claim.isCompleted()) {
            return;
        }
        if (!claim.isProcessing()) {
            throw new RetryableMessageException("event is already being processed: " + event.eventId());
        }

        try {
            orderService.cancelExpired(event);
            if (repository.completeConsumption(event.eventId(), claim.claimToken(), clock.instant()) != 1) {
                throw new RetryableMessageException("consumption lease was fenced: " + event.eventId());
            }
        } catch (RetryableMessageException | NonRetryableMessageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw classified(exception);
        }
    }

    private RuntimeException classified(RuntimeException exception) {
        if (failureClassifier.classify(exception) == FailureClassifier.RETRYABLE) {
            return new RetryableMessageException(messageOf(exception), exception);
        }
        return new NonRetryableMessageException(messageOf(exception), exception);
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-retry-count");
        if (value == null) {
            value = message.getMessageProperties().getHeaders().get("retry-count");
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException exception) {
                throw new NonRetryableMessageException("invalid retry count header", exception);
            }
        }
        return 0;
    }

    private String messageOf(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
