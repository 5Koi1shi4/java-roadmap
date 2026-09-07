package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.io.IOException;
import java.time.Duration;

/** Dedicated warranty queue consumer. Its binding is intentionally limited to
 * WARRANTY_REFUND_REQUESTED so unrelated events cannot be silently consumed. */
public final class WarrantyEventConsumer {
    private static final String CONSUMER = "campus-market-warranty";
    private final EventEnvelopeCodec codec;
    private final InboxRepository inbox;
    private final EventBusinessHandler handler;

    public WarrantyEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                 EventBusinessHandler handler) {
        this.codec = codec;
        this.inbox = inbox;
        this.handler = handler;
    }

    @RabbitListener(queues = RabbitTopology.WARRANTY_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEvent event = codec.decode(message.getBody());
            InboxRepository.DeliveryResult result = inbox.processForDelivery(CONSUMER, event.eventId(),
                Duration.ofMinutes(1), claim -> handler.handle(event));
            if (result == InboxRepository.DeliveryResult.COMPLETED
                || result == InboxRepository.DeliveryResult.PERMANENT_FAILED
                || result == InboxRepository.DeliveryResult.FAILED) {
                channel.basicAck(tag, false);
            } else {
                channel.basicNack(tag, false, true);
            }
        } catch (EventBusinessHandler.UnsupportedEventException unsupported) {
            // InboxRepository normally converts this to PERMANENT_FAILED; the
            // fallback keeps malformed/custom implementations recoverable via DLX.
            channel.basicNack(tag, false, false);
        } catch (IllegalArgumentException permanent) {
            channel.basicAck(tag, false);
        } catch (RuntimeException retryable) {
            channel.basicNack(tag, false, true);
        }
    }
}
