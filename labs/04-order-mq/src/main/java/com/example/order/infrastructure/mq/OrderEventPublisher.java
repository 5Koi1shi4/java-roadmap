package com.example.order.infrastructure.mq;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Publishes an order event and waits for RabbitMQ publisher confirmation. */
@Component
public class OrderEventPublisher {
    static final String TIMEOUT_EXCHANGE = "order.timeout.exchange";
    static final String TIMEOUT_ROUTING_KEY = "order.timeout.1m";
    private static final long CONFIRM_TIMEOUT_MILLIS = 10_000L;

    private final RabbitTemplate rabbitTemplate;
    private final String timeoutRoutingKey;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this(rabbitTemplate, TIMEOUT_ROUTING_KEY);
    }

    @Autowired
    public OrderEventPublisher(RabbitTemplate rabbitTemplate,
                               @Value("${order.timeout.routing-key:order.timeout.1m}") String timeoutRoutingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.timeoutRoutingKey = timeoutRoutingKey;
    }

    public void publish(OutboxEvent event) {
        Boolean acknowledged = rabbitTemplate.invoke(operations -> {
            CorrelationData correlation = new CorrelationData(event.eventId().toString());
            operations.convertAndSend(TIMEOUT_EXCHANGE, timeoutRoutingKey, event.payload(), correlation);
            return operations.waitForConfirms(CONFIRM_TIMEOUT_MILLIS);
        });
        if (!Boolean.TRUE.equals(acknowledged)) {
            throw new AmqpException("publisher confirm nack for event " + event.eventId());
        }
    }
}
