package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/** Rabbit MANUAL ack 适配器：提交完成后 ACK，业务异常则 NACK/requeue。 */
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
                boolean durableFailure = inbox.claim(CONSUMER, eventId, Duration.ofMinutes(1))
                    .map(claim -> claim.alreadyCompleted() || claim.failed()
                        || (!claim.alreadyCompleted() && !claim.failed()
                            && inbox.markFailed(CONSUMER, eventId, claim.ownerId(), claim.claimToken()) == 1))
                    .orElse(false);
                if (durableFailure && publishManual(eventId, "PERMANENT")) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicNack(tag, false, true);
                }
            } catch (RuntimeException noUsableId) {
                channel.basicNack(tag, false, false);
            }
            return;
        }
        try {
            InboxRepository.DeliveryResult result = inbox.processForDelivery(CONSUMER, event.eventId(), Duration.ofMinutes(1),
                claim -> businessHandler.handle(event));
            if (result == InboxRepository.DeliveryResult.COMPLETED) channel.basicAck(tag, false);
            else if (result == InboxRepository.DeliveryResult.PERMANENT_FAILED) {
                if (publishManual(event.eventId(), "PERMANENT")) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicNack(tag, false, true);
                }
            } else if (result == InboxRepository.DeliveryResult.FAILED) {
                if (publishManual(event.eventId(), "EXHAUSTED")) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicNack(tag, false, true);
                }
            } else channel.basicNack(tag, false, true);
        } catch (IllegalArgumentException permanent) {
            if (publishManual(event.eventId(), "PERMANENT")) {
                channel.basicAck(tag, false);
            } else {
                channel.basicNack(tag, false, true);
            }
        } catch (RuntimeException retryable) {
            channel.basicNack(tag, false, true);
        }
    }

    private boolean publishManual(UUID eventId, String failureClass) {
        try {
            manualPublisher.publish(eventId, failureClass);
            return true;
        } catch (RuntimeException ignored) {
            // manual_failure is durable; keep the broker delivery retryable when
            // its notification cannot yet be routed or confirmed.
            return false;
        }
    }
}
