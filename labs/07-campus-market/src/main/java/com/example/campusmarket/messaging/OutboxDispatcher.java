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
            // A lease that expired after the third delivery attempt is a crash
            // takeover, not a fourth publish. Failing it here keeps the source
            // row and its durable manual copy in one REQUIRES_NEW transaction.
            if (message.attemptCount() >= 3) {
                if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "EXHAUSTED") == 1) {
                    publishManualFailure(message.eventId(), "EXHAUSTED");
                }
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
                publishWithConfirm(message.eventId().toString(), message.eventType(),
                    new Message(codec.encode(event), properties));
                completed += repository.complete(message.eventId(), message.ownerId(), message.claimToken());
            } catch (RuntimeException failure) {
                if (failure instanceof IllegalArgumentException) {
                    if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "PERMANENT") == 1) {
                        publishManualFailure(message.eventId(), "PERMANENT");
                    }
                } else if (message.attemptCount() >= 3) {
                    if (repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "EXHAUSTED") == 1) {
                        publishManualFailure(message.eventId(), "EXHAUSTED");
                    }
                } else {
                    repository.releaseForRetry(message.eventId(), message.ownerId(), message.claimToken(), Duration.ofSeconds(1));
                }
            }
        }
        return completed;
    }

    /** 人工副本已在 REQUIRES_NEW 事务中落库；人工交换机暂不可用时保留 NEW 供补偿调度。 */
    private void publishManualFailure(java.util.UUID eventId, String failureClass) {
        try {
            manualFailurePublisher.publish(eventId, failureClass);
        } catch (RuntimeException ignored) {
            // 不将人工通知的瞬时投递失败误报为业务成功，也不泄露 payload/连接细节。
        }
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
}
