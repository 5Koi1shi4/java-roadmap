package com.example.campusmarket.product.event;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/** 数据库事务提交后才 ACK；运行故障最多尝试三次，不合法或耗尽事件进入 broker 死信路径。 */
@Component
public class ProductRabbitListener {
    public static final String PRODUCT_QUEUE = "campus.product.read";
    private static final int MAX_ATTEMPTS = 3;

    private final ProductEventConsumer consumer;

    public ProductRabbitListener(ProductEventConsumer consumer) {
        this.consumer = Objects.requireNonNull(consumer, "商品消费者不能为空");
    }

    @RabbitListener(queues = PRODUCT_QUEUE)
    public void handle(Message message, Channel channel) throws IOException {
        Objects.requireNonNull(message, "商品消息不能为空");
        Objects.requireNonNull(channel, "Rabbit Channel不能为空");
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                consumer.accept(message.getBody());
            } catch (IllegalArgumentException invalidProtocol) {
                channel.basicReject(deliveryTag, false);
                return;
            } catch (RuntimeException retryableFailure) {
                if (attempt == MAX_ATTEMPTS) {
                    try {
                        consumer.markBlockedAfterExhausted();
                    } catch (RuntimeException unavailable) {
                        // 读库故障时独立事务可能无法落 BLOCKED；专用持久死信队列仍使
                        // 恢复后的 projection health 保持 DOWN，避免后续屏障误报 READY。
                    }
                    channel.basicReject(deliveryTag, false);
                    return;
                }
                continue;
            }
            channel.basicAck(deliveryTag, false);
            return;
        }
    }
}
