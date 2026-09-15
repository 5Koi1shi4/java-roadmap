package com.example.campusmarket.product.event;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/** 数据库事务提交后才 ACK；不合法事件进入 broker 死信路径。 */
@Component
public class ProductRabbitListener {
    public static final String PRODUCT_QUEUE = "campus.product.read";

    private final ProductEventConsumer consumer;

    public ProductRabbitListener(ProductEventConsumer consumer) {
        this.consumer = Objects.requireNonNull(consumer, "商品消费者不能为空");
    }

    @RabbitListener(queues = PRODUCT_QUEUE)
    public void handle(Message message, Channel channel) throws IOException {
        Objects.requireNonNull(message, "商品消息不能为空");
        Objects.requireNonNull(channel, "Rabbit Channel不能为空");
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            consumer.accept(message.getBody());
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException invalidProtocol) {
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException retryableFailure) {
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
