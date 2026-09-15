package com.example.campusmarket.product.event;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Rabbit ACK 只发生在读库事务返回后；失败保留可重试或人工排查路径。 */
class ProductRabbitListenerTest {
    private final ProductEventConsumer consumer = mock(ProductEventConsumer.class);
    private final Channel channel = mock(Channel.class);
    private final ProductRabbitListener listener = new ProductRabbitListener(consumer);

    @Test
    void acknowledgesOnlyAfterConsumerReturns() throws Exception {
        Message message = message();

        listener.handle(message, channel);

        var calls = inOrder(consumer, channel);
        calls.verify(consumer).accept(message.getBody());
        calls.verify(channel).basicAck(9L, false);
    }

    @Test
    void transientReadDatabaseFailureNacksForRetryWithoutAck() throws Exception {
        Message message = message();
        doThrow(new IllegalStateException("读库断开")).when(consumer).accept(message.getBody());

        listener.handle(message, channel);

        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(9L, false);
    }

    @Test
    void invalidProtocolRejectsToDeadLetterWithoutAck() throws Exception {
        Message message = message();
        doThrow(new IllegalArgumentException("schemaVersion 无效"))
            .when(consumer).accept(message.getBody());

        listener.handle(message, channel);

        verify(channel).basicReject(9L, false);
        verify(channel, never()).basicAck(9L, false);
    }

    private static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(9L);
        return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }
}
