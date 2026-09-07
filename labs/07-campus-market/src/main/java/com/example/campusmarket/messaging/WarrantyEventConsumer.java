package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;
import com.example.campusmarket.observability.CampusMetrics;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Dedicated warranty queue consumer. Its binding is intentionally limited to
 * WARRANTY_REFUND_REQUESTED so unrelated events cannot be silently consumed. */
public final class WarrantyEventConsumer {
    private static final String CONSUMER = "campus-market-warranty";
    private final EventEnvelopeCodec codec;
    private final InboxRepository inbox;
    private final EventBusinessHandler handler;
    private final CampusMetrics metrics;

    public WarrantyEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                 EventBusinessHandler handler) {
        this(codec, inbox, handler, null);
    }

    public WarrantyEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                 EventBusinessHandler handler, CampusMetrics metrics) {
        this.codec = codec;
        this.inbox = inbox;
        this.handler = handler;
        this.metrics = metrics;
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
                || result == InboxRepository.DeliveryResult.FAILED
                || result == InboxRepository.DeliveryResult.LEASE_TAKEOVER) {
                if (result == InboxRepository.DeliveryResult.COMPLETED) recordInbox("COMPLETED");
                else recordInbox("FAILED");
                if (result == InboxRepository.DeliveryResult.LEASE_TAKEOVER && metrics != null) metrics.recordLeaseTakeover("INBOX");
                channel.basicAck(tag, false);
            } else {
                if (metrics != null) metrics.recordRetry("INBOX", "RETRY");
                channel.basicNack(tag, false, true);
            }
        } catch (EventBusinessHandler.UnsupportedEventException unsupported) {
            // InboxRepository normally converts this to PERMANENT_FAILED; the
            // fallback keeps malformed/custom implementations recoverable via DLX.
            channel.basicNack(tag, false, false);
        } catch (IllegalArgumentException malformedEnvelope) {
            handleMalformedEnvelope(message, channel, tag);
        } catch (RuntimeException retryable) {
            if (metrics != null) metrics.recordRetry("INBOX", "RETRY");
            channel.basicNack(tag, false, true);
        }
    }

    /**
     * A codec failure happens before normal Inbox processing, so preserve a
     * trustworthy broker message id in the same durable failure records first.
     * Without an id there is no safe event key to persist and the delivery must
     * be rejected to the warranty queue's DLX.
     */
    private void handleMalformedEnvelope(Message message, Channel channel, long tag) throws IOException {
        UUID eventId;
        try {
            eventId = UUID.fromString(message.getMessageProperties().getMessageId());
        } catch (RuntimeException noReliableId) {
            channel.basicNack(tag, false, false);
            return;
        }
        try {
            Optional<InboxRepository.Claim> claimed = inbox.claim(CONSUMER, eventId, Duration.ofMinutes(1));
            boolean durableFailure = claimed.map(claim -> claim.alreadyCompleted() || claim.failed()
                || inbox.markFailed(CONSUMER, eventId, claim.ownerId(), claim.claimToken()) == 1).orElse(false);
            if (durableFailure) { recordInbox("FAILED"); channel.basicAck(tag, false); }
            else { if (metrics != null) metrics.recordRetry("INBOX", "RETRY"); channel.basicNack(tag, false, true); }
        } catch (RuntimeException persistenceFailure) {
            channel.basicNack(tag, false, true);
        }
    }

    private void recordInbox(String status) { if (metrics != null) metrics.recordInbox(status); }
}
