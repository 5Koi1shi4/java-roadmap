package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/** Rabbit MANUAL ack 适配器：提交完成后 ACK，业务异常则 NACK/requeue。 */
@Component
@Profile("!test")
public class ReliableEventConsumer {
    private static final String CONSUMER = "campus-market-order";
    private final EventEnvelopeCodec codec;
    private final InboxRepository inbox;
    private final EventBusinessHandler businessHandler;
    private final ManualFailurePublisher manualPublisher;

    public ReliableEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                 EventBusinessHandler businessHandler, ManualFailurePublisher manualPublisher) {
        this.codec = codec;
        this.inbox = inbox;
        this.businessHandler = businessHandler;
        this.manualPublisher = manualPublisher;
    }

    @RabbitListener(queues = RabbitTopology.EVENT_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        DomainEvent event;
        try {
            event = codec.decode(message.getBody());
        } catch (IllegalArgumentException invalid) {
            String messageId = message.getMessageProperties().getMessageId();
            try {
                UUID eventId = UUID.fromString(messageId);
                inbox.claim(CONSUMER, eventId, Duration.ofMinutes(1)).ifPresent(claim -> {
                    if (!claim.alreadyCompleted()) {
                        inbox.markFailed(CONSUMER, eventId, claim.ownerId(), claim.claimToken());
                    }
                });
                manualPublisher.publish(eventId, "PERMANENT");
                channel.basicAck(tag, false);
            } catch (RuntimeException noUsableId) {
                channel.basicNack(tag, false, false);
            }
            return;
        }
        try {
            boolean acknowledged = inbox.process(CONSUMER, event.eventId(), Duration.ofMinutes(1),
                claim -> businessHandler.handle(event));
            if (acknowledged) channel.basicAck(tag, false);
            else if (inbox.isFailed(CONSUMER, event.eventId())) {
                manualPublisher.publish(event.eventId(), "EXHAUSTED");
                channel.basicAck(tag, false);
            } else channel.basicNack(tag, false, true);
        } catch (IllegalArgumentException permanent) {
            manualPublisher.publish(event.eventId(), "PERMANENT");
            channel.basicAck(tag, false);
        } catch (RuntimeException retryable) {
            channel.basicNack(tag, false, true);
        }
    }
}
