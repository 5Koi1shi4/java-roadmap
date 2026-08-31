package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 在数据库领取成功后发布事件，并以 publisher confirm 驱动状态完成。 */
@Component
@ConditionalOnBean({OutboxRepository.class, RabbitTemplate.class})
public class OutboxDispatcher {
    public static final String EXCHANGE = RabbitTopology.EVENT_EXCHANGE;
    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final EventEnvelopeCodec codec;
    private final String owner;
    private final ConcurrentMap<String, ReturnedMessage> returnedMessages = new ConcurrentHashMap<>();

    public OutboxDispatcher(OutboxRepository repository, RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.repository = Objects.requireNonNull(repository, "outbox repository 不能为空");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.owner = "dispatcher-" + java.util.UUID.randomUUID();
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
        }
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> {
            String messageId = returned.getMessage().getMessageProperties().getMessageId();
            if (messageId != null) returnedMessages.put(messageId, returned);
        });
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
                rabbitTemplate.invoke(operations -> {
                    operations.send(EXCHANGE, message.eventType(), new Message(codec.encode(event), properties));
                    operations.waitForConfirmsOrDie(10_000L);
                    return null;
                });
                if (returnedMessages.remove(message.eventId().toString()) != null) {
                    throw new IllegalStateException("事件不可路由");
                }
                completed += repository.complete(message.eventId(), message.ownerId(), message.claimToken());
            } catch (RuntimeException failure) {
                if (failure instanceof IllegalArgumentException) {
                    repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "PERMANENT");
                } else if (message.attemptCount() >= 3) {
                    repository.fail(message.eventId(), message.ownerId(), message.claimToken(), "EXHAUSTED");
                } else {
                    repository.releaseForRetry(message.eventId(), message.ownerId(), message.claimToken(), Duration.ofSeconds(1));
                }
            }
        }
        return completed;
    }
}
