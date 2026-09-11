package com.example.campusmarket.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 在失败记录事务提交后投递人工处理通知。 */
@Component
@Profile("!test")
public class ManualFailurePublisher {
    private final RabbitTemplate rabbitTemplate;

    public ManualFailurePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
        }
        rabbitTemplate.setMandatory(true);
    }

    public void publish(UUID sourceId, String failureClass) {
        if (sourceId == null || (!"PERMANENT".equals(failureClass) && !"EXHAUSTED".equals(failureClass))) {
            throw new IllegalArgumentException("人工消息参数无效");
        }
        publishMessage(sourceId.toString(), failureClass,
            ("{\"sourceId\":\"" + sourceId + "\",\"failureClass\":\"" + failureClass + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 发布持久副本本身；调度器只有在该 publish 成功后才完成副本。 */
    public void publish(ManualFailureScheduler.ManualFailure failure) {
        if (failure == null) throw new IllegalArgumentException("人工副本不能为空");
        publishMessage(failure.id().toString(), failure.failureClass(),
            failure.payload().getBytes(StandardCharsets.UTF_8));
    }

    private void publishMessage(String messageId, String failureClass, byte[] payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(messageId);
        rabbitTemplate.invoke(ops -> {
            CorrelationData correlation = new CorrelationData(properties.getMessageId());
            ops.send(RabbitTopology.MANUAL_EXCHANGE, "FAILURE", new Message(payload, properties), correlation);
            try {
                // See OutboxDispatcher.awaitConfirmed: Spring AMQP completes
                // this correlation only after any returned message is attached.
                CorrelationData.Confirm confirm = correlation.getFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("人工消息不可路由");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("人工消息确认被中断", e);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new IllegalStateException("人工消息确认失败", e);
            }
            return null;
        });
    }
}
