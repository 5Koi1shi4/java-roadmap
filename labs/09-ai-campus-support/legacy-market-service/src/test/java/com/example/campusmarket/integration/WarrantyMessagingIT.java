package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.messaging.RabbitTopology;
import com.example.campusmarket.messaging.EventEnvelopeCodec;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.shared.DomainEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Low-memory end-to-end messaging proof: MySQL outbox, real Rabbit confirm/
 * binding, listener Inbox transaction and replay fencing. */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "spring.rabbitmq.listener.simple.auto-startup=true",
    "spring.rabbitmq.listener.direct.auto-startup=true",
    "campus.market.payment.provider-url=http://127.0.0.1:1/unreachable"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarrantyMessagingIT extends Task12RabbitMySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RabbitTemplate rabbit;
    @Autowired EventEnvelopeCodec codec;
    @MockitoBean RefundService refunds;

    @Test
    void outboxConfirmRoutesToWarrantyInboxAndReplayIsIdempotent() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID(), caseId = UUID.randomUUID(), obligationId = UUID.randomUUID();
        String payload = "{\"orderId\":\"" + orderId + "\",\"caseId\":\"" + caseId
            + "\",\"obligationId\":\"" + obligationId + "\",\"amountFen\":1200}";
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?, 'WARRANTY_REFUND_REQUESTED',?,?,1,?,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), eventId.toString(), obligationId.toString(), 1L,
            java.sql.Timestamp.from(Instant.now()), payload);

        assertThat(dispatcher.dispatchOnce(1)).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name='campus-market-warranty' AND event_id=?", String.class, eventId.toString())).isEqualTo("COMPLETED"));
        verify(refunds, times(1)).requestRefund(any(), any(), any(), any(), any());
        assertThat(rabbit.receive(RabbitTopology.EVENT_QUEUE, 100)).isNull();

        // Re-publish the exact captured event. Inbox COMPLETED is the source
        // of idempotence; the business handler must not run again.
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId.toString());
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        Message replay = new Message(codec.encode(new DomainEvent(eventId, "WARRANTY_REFUND_REQUESTED",
            obligationId.toString(), 1, Instant.now(), 1,
            Map.of("orderId", orderId.toString(), "caseId", caseId.toString(),
                "obligationId", obligationId.toString(), "amountFen", 1200))), properties);
        rabbit.send(RabbitTopology.EVENT_EXCHANGE, "WARRANTY_REFUND_REQUESTED", replay);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            verify(refunds, times(1)).requestRefund(any(), any(), any(), any(), any()));
    }

    @Test
    void malformedEnvelopeWithReliableMessageIdIsPersistedAsPermanentFailureBeforeAck() {
        UUID eventId = UUID.randomUUID();
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId.toString());
        Message malformed = new Message("{not-json".getBytes(StandardCharsets.UTF_8), properties);

        rabbit.send(RabbitTopology.EVENT_EXCHANGE, "WARRANTY_REFUND_REQUESTED", malformed);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
                String.class, "campus-market-warranty", eventId.toString())).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT failure_class FROM manual_failure WHERE source_type='INBOX' AND source_id=? AND consumer_name=?",
                String.class, eventId.toString(), "campus-market-warranty")).isEqualTo("PERMANENT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE consumer_name=? AND event_id=? AND status='PROCESSING'",
                Integer.class, "campus-market-warranty", eventId.toString())).isZero();
        });
    }

    @Test
    void malformedEnvelopeWithoutReliableMessageIdIsDeadLettered() {
        MessageProperties properties = new MessageProperties();
        Message malformed = new Message("{not-json".getBytes(StandardCharsets.UTF_8), properties);

        rabbit.send(RabbitTopology.EVENT_EXCHANGE, "WARRANTY_REFUND_REQUESTED", malformed);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(rabbit.receive(RabbitTopology.MANUAL_QUEUE, 100)).isNotNull());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE consumer_name=? AND status='PROCESSING'",
            Integer.class, "campus-market-warranty")).isZero();
    }
}
