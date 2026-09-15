package com.example.campusmarket.catalog.search;

import com.example.campusmarket.messaging.RabbitTopology;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 以 correlated publisher confirm 发布完整商品快照。 */
@Component
@Profile("!test")
public final class ProductEventPublisher {
    private static final long CONFIRM_TIMEOUT_SECONDS = 10;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper mapper;

    public ProductEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper mapper) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate 不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 不能为空");
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
        }
        rabbitTemplate.setMandatory(true);
    }

    /**
     * 只在 Rabbit confirm ACK 且没有 returned message 时返回；任何其他结果
     * 都让调用方保留源事件并按租约状态处理。
     */
    public void publish(ProductSnapshotEvent event) {
        Objects.requireNonNull(event, "商品事件不能为空");
        final byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("商品事件序列化失败", e);
        }

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(event.eventId().toString());
        properties.setType(event.eventType());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.invoke(operations -> {
            CorrelationData correlation = new CorrelationData(event.eventId().toString());
            operations.send(RabbitTopology.PRODUCT_EXCHANGE, event.eventType(),
                new Message(payload, properties), correlation);
            awaitConfirmed(correlation);
            return null;
        });
    }

    /** 健康门禁只探测 broker，不领取或消耗 outbox 尝试次数。 */
    public boolean brokerHealthy() {
        Connection connection = null;
        try {
            connection = rabbitTemplate.getConnectionFactory().createConnection();
            return connection.isOpen();
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // health 门禁已经按连接创建结果判定；关闭失败不能改变该结论。
                }
            }
        }
    }

    private static void awaitConfirmed(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Rabbit publisher NACK");
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("商品事件不可路由");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Rabbit confirm 被中断", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Rabbit confirm 超时或失败", e);
        }
    }
}
