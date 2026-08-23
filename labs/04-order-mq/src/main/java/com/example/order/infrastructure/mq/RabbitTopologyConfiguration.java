package com.example.order.infrastructure.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Rabbit topology and listener retry policy for timeout events. */
@Configuration
public class RabbitTopologyConfiguration {
    public static final String TIMEOUT_EXCHANGE = "order.timeout.exchange";
    public static final String CANCEL_EXCHANGE = "order.cancel.exchange";
    public static final String TIMEOUT_QUEUE_10S = "order.timeout.10s.queue";
    public static final String TIMEOUT_QUEUE_1M = "order.timeout.1m.queue";
    public static final String TIMEOUT_QUEUE_5M = "order.timeout.5m.queue";
    public static final String CANCEL_QUEUE = "order.cancel.queue";
    public static final String MANUAL_QUEUE = "order.manual.queue";
    public static final String TIMEOUT_ROUTING_KEY = "order.timeout.1m";
    public static final String CANCEL_ROUTING_KEY = "order.cancel";

    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(TIMEOUT_EXCHANGE, true, false);
    }

    @Bean
    public FailureClassifier failureClassifier() {
        return new FailureClassifier();
    }

    @Bean
    public DirectExchange orderCancelExchange() {
        return new DirectExchange(CANCEL_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderTimeout10sQueue() {
        return timeoutQueue(TIMEOUT_QUEUE_10S, 10_000L);
    }

    @Bean
    public Queue orderTimeout1mQueue() {
        return timeoutQueue(TIMEOUT_QUEUE_1M, 60_000L);
    }

    @Bean
    public Queue orderTimeout5mQueue() {
        return timeoutQueue(TIMEOUT_QUEUE_5M, 300_000L);
    }

    private Queue timeoutQueue(String name, long ttl) {
        return new Queue(name, true, false, false, Map.of(
                "x-message-ttl", ttl,
                "x-dead-letter-exchange", CANCEL_EXCHANGE,
                "x-dead-letter-routing-key", CANCEL_ROUTING_KEY));
    }

    @Bean
    public Queue orderCancelQueue() {
        return new Queue(CANCEL_QUEUE, true);
    }

    @Bean
    public Queue orderManualQueue() {
        return new Queue(MANUAL_QUEUE, true);
    }

    @Bean
    public Binding timeout1mBinding(@Qualifier("orderTimeout1mQueue") Queue orderTimeout1mQueue,
                                    @Qualifier("orderTimeoutExchange") DirectExchange orderTimeoutExchange) {
        return BindingBuilder.bind(orderTimeout1mQueue).to(orderTimeoutExchange).with(TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding timeout10sBinding(@Qualifier("orderTimeout10sQueue") Queue queue,
                                     @Qualifier("orderTimeoutExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("order.timeout.10s");
    }

    @Bean
    public Binding timeout5mBinding(@Qualifier("orderTimeout5mQueue") Queue queue,
                                   @Qualifier("orderTimeoutExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("order.timeout.5m");
    }

    @Bean
    public Binding cancelBinding(@Qualifier("orderCancelQueue") Queue orderCancelQueue,
                                 @Qualifier("orderCancelExchange") DirectExchange orderCancelExchange) {
        return BindingBuilder.bind(orderCancelQueue).to(orderCancelExchange).with(CANCEL_ROUTING_KEY);
    }

    @Bean
    public RetryOperationsInterceptor timeoutRetryInterceptor(MessageRecoverer timeoutMessageRecoverer) {
        SimpleRetryPolicy policy = new SimpleRetryPolicy(
                3, Map.of(RetryableMessageException.class, true), true);
        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(1_000L);
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(policy)
                .backOffPolicy(backOff)
                .recoverer(timeoutMessageRecoverer)
                .build();
    }

    @Bean
    public MessageRecoverer timeoutMessageRecoverer(RabbitTemplate rabbitTemplate,
                                                     FailureClassifier classifier,
                                                     JdbcOrderRepository repository,
                                                     ObjectMapper objectMapper) {
        return (message, failure) -> {
            MessageProperties properties = new MessageProperties();
            Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
            int inboundRetryCount = retryCountHeader(headers);
            Object exhausted = headers.get("x-retry-exhausted");
            headers.remove("x-retry-count");
            properties.setHeaders(headers);
            properties.setContentType(message.getMessageProperties().getContentType());
            FailureClassifier.Category category = classifier.classify(failure);
            String failureMessage = messageOf(failure);
            properties.setHeader("failure-category", category.name());
            properties.setHeader("failure-message", failureMessage);
            int attempts = RetrySynchronizationManager.getContext() == null
                    ? 1 : RetrySynchronizationManager.getContext().getRetryCount() + 1;
            if (Boolean.TRUE.equals(exhausted) || "true".equalsIgnoreCase(String.valueOf(exhausted))) {
                attempts = Math.max(attempts, inboundRetryCount);
            }
            properties.setHeader("x-retry-count", attempts);
            UUID eventId = eventId(message, objectMapper);
            String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            Message outbound = new Message(message.getBody(), properties);
            long failureId = 0L;
            RuntimeException databaseFailure = null;
            try {
                failureId = repository.insertManualFailure(
                        eventId, payload, category.name(), failureMessage, attempts, Instant.now());
            } catch (RuntimeException exception) {
                // The database is the preferred durable copy, but it is not the only safe path.
                databaseFailure = exception;
            }
            Boolean confirmed;
            try {
                confirmed = rabbitTemplate.invoke(operations -> {
                    operations.send("", MANUAL_QUEUE, outbound);
                    return operations.waitForConfirms(10_000L);
                });
            } catch (RuntimeException exception) {
                if (databaseFailure != null) {
                    throw new ImmediateRequeueAmqpException(
                            "manual queue and durable fallback unavailable", exception);
                }
                throw new AmqpRejectAndDontRequeueException("manual queue publish failed", exception);
            }
            if (!Boolean.TRUE.equals(confirmed)) {
                if (databaseFailure != null) {
                    throw new ImmediateRequeueAmqpException(
                            "manual queue confirm failed and durable fallback unavailable");
                }
                throw new AmqpRejectAndDontRequeueException("manual queue publisher confirm nack");
            }
            if (databaseFailure != null) {
                return;
            }
            repository.markManualDelivered(failureId, Instant.now());
            if (eventId != null) {
                repository.recordConsumptionFailure(eventId, category.name(), failureMessage, Instant.now());
            }
        };
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, RetryOperationsInterceptor timeoutRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(timeoutRetryInterceptor);
        return factory;
    }

    private static UUID eventId(Message message, ObjectMapper objectMapper) {
        Object header = message.getMessageProperties().getHeaders().get("event-id");
        if (header != null) {
            try {
                return UUID.fromString(header.toString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        try {
            JsonNode node = objectMapper.readTree(message.getBody());
            JsonNode value = node.get("eventId");
            return value == null ? null : UUID.fromString(value.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int retryCountHeader(Map<String, Object> headers) {
        Object value = headers.get("x-retry-count");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 1 : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
