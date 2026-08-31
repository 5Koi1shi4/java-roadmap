package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 在数据库领取成功后发布事件，并以 publisher confirm 驱动状态完成。 */
@Component
public class OutboxDispatcher {
    public static final String EXCHANGE = RabbitTopology.EVENT_EXCHANGE;
    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final EventEnvelopeCodec codec;
    private final ManualFailurePublisher manualFailurePublisher;
    private final String owner;

    public OutboxDispatcher(OutboxRepository repository, RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec,
                            ManualFailurePublisher manualFailurePublisher) {
        this.repository = Objects.requireNonNull(repository, "outbox repository 不能为空");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.manualFailurePublisher = Objects.requireNonNull(manualFailurePublisher, "人工发布器不能为空");
        this.owner = "dispatcher-" + java.util.UUID.randomUUID();
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
        }
        rabbitTemplate.setMandatory(true);
    }

    public int dispatchOnce(int limit) {
        return dispatchOnce(limit, Duration.ofSeconds(30));
    }

    public int dispatchOnce(int limit, Duration lease) {
        int completed = 0;
        for (OutboxRepository.OutboxMessage message : repository.claimBatch(owner, limit, lease)) {
            try {
                DomainEvent event = new DomainEvent(message.eventId(), message.eventType(), message.aggregateId(),
                    message.aggregateVersion(), message.occurredAt(), message.schemaVersion(),
                    codec.decodePayload(message.payloadJson()));
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setMessageId(message.eventId().toString());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                publishWithConfirm(message.eventId().toString(), message.eventType(),
                    new Message(codec.encode(event), properties));
                completed += repository.complete(message.eventId(), message.ownerId(), message.claimToken());
            } catch (RuntimeException failure) {
                if (failure instanceof IllegalArgumentException) {
                    if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "PERMANENT") == 1) {
                        manualFailurePublisher.publish(message.eventId(), "PERMANENT");
                    }
                } else if (message.attemptCount() >= 3) {
                    if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "EXHAUSTED") == 1) {
                        manualFailurePublisher.publish(message.eventId(), "EXHAUSTED");
                    }
                } else {
                    repository.releaseForRetry(message.eventId(), message.ownerId(), message.claimToken(), Duration.ofSeconds(1));
                }
            }
        }
        return completed;
    }

    private void publishWithConfirm(String correlationId, String routingKey, Message message) {
        rabbitTemplate.invoke(operations -> {
            CorrelationData correlation = new CorrelationData(correlationId);
            operations.send(EXCHANGE, routingKey, message, correlation);
            awaitConfirmed(correlation);
            return null;
        });
    }

    private static void awaitConfirmed(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("Rabbit publisher NACK");
            if (correlation.getReturned() != null) throw new IllegalStateException("事件不可路由");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Rabbit confirm 被中断", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Rabbit confirm 超时或失败", e);
        }
    }
}
