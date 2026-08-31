package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 在数据库领取成功后发布事件，并以 publisher confirm 驱动状态完成。 */
@Component
@ConditionalOnBean({OutboxRepository.class, RabbitTemplate.class})
public class OutboxDispatcher {
    public static final String EXCHANGE = RabbitTopology.EVENT_EXCHANGE;
    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final EventEnvelopeCodec codec;
    private final String owner;

    public OutboxDispatcher(OutboxRepository repository, RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.repository = Objects.requireNonNull(repository, "outbox repository 不能为空");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.owner = "dispatcher-" + java.util.UUID.randomUUID();
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
        }
    }

    public int dispatchOnce(int limit) {
        return dispatchOnce(limit, Duration.ofSeconds(30));
    }

    public int dispatchOnce(int limit, Duration lease) {
        int completed = 0;
        for (OutboxRepository.OutboxMessage message : repository.claimBatch(owner, limit, lease)) {
            try {
                DomainEvent event = new DomainEvent(message.eventId(), message.eventType(), message.aggregateId(),
                    message.aggregateVersion(), Instant.now(), message.schemaVersion(),
                    codec.decodePayload(message.payloadJson()));
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setMessageId(message.eventId().toString());
                rabbitTemplate.invoke(operations -> {
                    operations.send(EXCHANGE, message.eventType(), new Message(codec.encode(event), properties));
                    operations.waitForConfirmsOrDie(10_000L);
                    return null;
                });
                completed += repository.complete(message.eventId(), message.ownerId(), message.claimToken());
            } catch (RuntimeException failure) {
                repository.releaseForRetry(message.eventId(), message.ownerId(), message.claimToken(), Duration.ofSeconds(1));
            }
        }
        return completed;
    }
}
