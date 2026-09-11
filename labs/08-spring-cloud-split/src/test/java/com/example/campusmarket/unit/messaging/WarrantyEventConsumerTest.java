package com.example.campusmarket.unit.messaging;

import com.example.campusmarket.messaging.EventBusinessHandler;
import com.example.campusmarket.messaging.EventEnvelopeCodec;
import com.example.campusmarket.messaging.InboxRepository;
import com.example.campusmarket.messaging.WarrantyEventConsumer;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WarrantyEventConsumerTest {
    @Test
    void malformedEnvelopeWithReliableMessageIdPersistsPermanentFailureBeforeAck() throws Exception {
        EventEnvelopeCodec codec = mock(EventEnvelopeCodec.class);
        InboxRepository inbox = mock(InboxRepository.class);
        Channel channel = mock(Channel.class);
        UUID eventId = UUID.randomUUID();
        var claim = new InboxRepository.Claim("campus-market-warranty", eventId, "owner", "token", false, false);
        when(codec.decode(any(byte[].class))).thenThrow(new IllegalArgumentException("invalid"));
        when(inbox.claim(eq("campus-market-warranty"), eq(eventId), any(Duration.class))).thenReturn(Optional.of(claim));
        when(inbox.markFailed("campus-market-warranty", eventId, "owner", "token")).thenReturn(1);

        WarrantyEventConsumer consumer = new WarrantyEventConsumer(codec, inbox, mock(EventBusinessHandler.class));
        consumer.onMessage(message(eventId.toString()), channel);

        verify(inbox).claim(eq("campus-market-warranty"), eq(eventId), any(Duration.class));
        verify(inbox).markFailed("campus-market-warranty", eventId, "owner", "token");
        verify(channel).basicAck(17L, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void malformedEnvelopeWithoutReliableMessageIdIsRejectedToDeadLetter() throws Exception {
        EventEnvelopeCodec codec = mock(EventEnvelopeCodec.class);
        InboxRepository inbox = mock(InboxRepository.class);
        Channel channel = mock(Channel.class);
        when(codec.decode(any(byte[].class))).thenThrow(new IllegalArgumentException("invalid"));

        WarrantyEventConsumer consumer = new WarrantyEventConsumer(codec, inbox, mock(EventBusinessHandler.class));
        consumer.onMessage(message(null), channel);

        verify(channel).basicNack(17L, false, false);
        verifyNoMoreInteractions(channel);
    }

    private static Message message(String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(17L);
        if (messageId != null) properties.setMessageId(messageId);
        return new Message("{not-json".getBytes(StandardCharsets.UTF_8), properties);
    }
}
