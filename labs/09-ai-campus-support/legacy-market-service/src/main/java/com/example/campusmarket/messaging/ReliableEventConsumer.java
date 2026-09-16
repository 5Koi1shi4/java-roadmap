package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.example.campusmarket.observability.CampusMetrics;
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
    private final CampusMetrics metrics;

    public ReliableEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                 EventBusinessHandler businessHandler) {
        this(codec, inbox, businessHandler, null);
    }

    public ReliableEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                 EventBusinessHandler businessHandler, CampusMetrics metrics) {
        this.codec = codec;
        this.inbox = inbox;
        this.businessHandler = businessHandler;
        this.metrics = metrics;
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
                if (durableFailure) {
                    recordInbox("FAILED");
                    channel.basicAck(tag, false);
                } else {
                    recordRetry();
                    channel.basicNack(tag, false, true);
                }
            } catch (RuntimeException noUsableId) {
                recordInbox("FAILED");
                channel.basicNack(tag, false, false);
            }
            return;
        }
        try {
            InboxRepository.DeliveryResult result = inbox.processForDelivery(CONSUMER, event.eventId(), Duration.ofMinutes(1),
                claim -> businessHandler.handle(event));
            if (result == InboxRepository.DeliveryResult.COMPLETED) { recordInbox("COMPLETED"); channel.basicAck(tag, false); }
            else if (result == InboxRepository.DeliveryResult.PERMANENT_FAILED) {
                recordInbox("FAILED");
                channel.basicAck(tag, false);
            } else if (result == InboxRepository.DeliveryResult.FAILED
                || result == InboxRepository.DeliveryResult.LEASE_TAKEOVER) {
                recordInbox("FAILED");
                if (result == InboxRepository.DeliveryResult.LEASE_TAKEOVER && metrics != null) metrics.recordLeaseTakeover("INBOX");
                channel.basicAck(tag, false);
            } else { recordRetry(); channel.basicNack(tag, false, true); }
        } catch (EventBusinessHandler.UnsupportedEventException unsupported) {
            // A queue bound to another event family must reject it; ACK would
            // mark the Inbox completed without executing business logic.
            channel.basicNack(tag, false, false);
        } catch (IllegalArgumentException permanent) {
            channel.basicAck(tag, false);
        } catch (RuntimeException retryable) {
            recordRetry();
            channel.basicNack(tag, false, true);
        }
    }

    private void recordInbox(String status) { if (metrics != null) metrics.recordInbox(status); }
    private void recordRetry() { if (metrics != null) metrics.recordRetry("INBOX", "RETRY"); }

}
