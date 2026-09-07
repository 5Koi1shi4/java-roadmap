package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.example.campusmarket.observability.CampusMetrics;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final EventPublisher eventPublisher;
    private final String owner;
    private final CampusMetrics metrics;

    @Autowired
    public OutboxDispatcher(OutboxRepository repository, RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec, CampusMetrics metrics) {
        this.repository = Objects.requireNonNull(repository, "outbox repository 不能为空");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.eventPublisher = this::publishWithConfirm;
        this.owner = "dispatcher-" + java.util.UUID.randomUUID();
        this.metrics = Objects.requireNonNull(metrics, "指标门面不能为空");
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
        }
        rabbitTemplate.setMandatory(true);
    }

    /** 供集成测试在 RabbitOperations/confirm 边界注入可控 NACK/timeout。 */
    public OutboxDispatcher(OutboxRepository repository, RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec,
                            EventPublisher eventPublisher) {
        this(repository, rabbitTemplate, codec, eventPublisher, null);
    }

    public OutboxDispatcher(OutboxRepository repository, RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec,
                            EventPublisher eventPublisher, CampusMetrics metrics) {
        this.repository = Objects.requireNonNull(repository, "outbox repository 不能为空");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "事件发布器不能为空");
        this.owner = "dispatcher-" + java.util.UUID.randomUUID();
        this.metrics = metrics;
    }

    public int dispatchOnce(int limit) {
        return dispatchOnce(limit, Duration.ofSeconds(30));
    }

    public int dispatchOnce(int limit, Duration lease) {
        int completed = 0;
        for (OutboxRepository.OutboxMessage message : repository.claimBatch(owner, limit, lease)) {
            // A lease that expired after the third delivery attempt is a crash
            // takeover, not a fourth publish. Failing it here keeps the source
            // row and its durable manual copy in one REQUIRES_NEW transaction.
            if (message.exhaustedTakeover()) {
                repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "EXHAUSTED");
                recordLeaseTakeover();
                continue;
            }
            try {
                DomainEvent event = new DomainEvent(message.eventId(), message.eventType(), message.aggregateId(),
                    message.aggregateVersion(), message.occurredAt(), message.schemaVersion(),
                    codec.decodePayload(message.payloadJson()));
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setMessageId(message.eventId().toString());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                eventPublisher.publish(message.eventType(),
                    new Message(codec.encode(event), properties));
                int changed = repository.complete(message.eventId(), message.ownerId(), message.claimToken());
                completed += changed;
                if (changed == 1) recordOutbox("PUBLISHED");
            } catch (RuntimeException failure) {
                if (failure instanceof IllegalArgumentException) {
                    if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "PERMANENT") == 1)
                        recordOutbox("FAILED");
                } else if (message.attemptCount() >= 3) {
                    if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "EXHAUSTED") == 1)
                        recordOutbox("FAILED");
                } else {
                    if (repository.releaseForRetry(message.eventId(), message.ownerId(), message.claimToken(), Duration.ofSeconds(1)) == 1)
                        recordRetry("RETRY");
                }
            }
        }
        return completed;
    }

    private void recordOutbox(String status) { if (metrics != null) metrics.recordOutbox(status); }
    private void recordRetry(String result) { if (metrics != null) metrics.recordRetry("OUTBOX", result); }
    private void recordLeaseTakeover() { if (metrics != null) metrics.recordLeaseTakeover("OUTBOX"); }

    private void publishWithConfirm(String routingKey, Message message) {
        rabbitTemplate.invoke(operations -> {
            CorrelationData correlation = new CorrelationData(message.getMessageProperties().getMessageId());
            operations.send(EXCHANGE, routingKey, message, correlation);
            awaitConfirmed(correlation);
            return null;
        });
    }

    private static void awaitConfirmed(CorrelationData correlation) {
        try {
            // Spring AMQP 3.2's PublisherCallbackChannelImpl first stores the
            // ReturnedMessage on this correlation, then waits for the return
            // callback before completing CorrelationData#getFuture. Therefore
            // this per-publish future is the single convergence point: a late
            // return cannot contaminate a later retry or another correlation.
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

    @FunctionalInterface
    public interface EventPublisher {
        void publish(String routingKey, Message message);
    }
}
