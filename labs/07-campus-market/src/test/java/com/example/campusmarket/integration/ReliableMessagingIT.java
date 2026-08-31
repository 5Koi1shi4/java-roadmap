package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.messaging.InboxRepository;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.messaging.OutboxRepository;
import com.example.campusmarket.messaging.RabbitTopology;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class ReliableMessagingIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outbox;
    @Autowired InboxRepository inbox;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM consumed_event WHERE consumer_name LIKE 'task6-%'");
        jdbc.update("DELETE FROM integration_outbox WHERE event_type='ORDER_CREATED' AND aggregate_id LIKE 'task6-%'");
    }

    @Test
    void concurrentDispatchersClaimOneOutboxMessage() throws Exception {
        String aggregate = "task6-" + UUID.randomUUID();
        UUID eventId = insertOutbox(aggregate);

        CompletableFuture<List<OutboxRepository.OutboxMessage>> first = CompletableFuture.supplyAsync(
            () -> outbox.claimBatch("task6-owner-a", 1, Duration.ofMinutes(1)));
        CompletableFuture<List<OutboxRepository.OutboxMessage>> second = CompletableFuture.supplyAsync(
            () -> outbox.claimBatch("task6-owner-b", 1, Duration.ofMinutes(1)));

        List<OutboxRepository.OutboxMessage> a = first.get(20, TimeUnit.SECONDS);
        List<OutboxRepository.OutboxMessage> b = second.get(20, TimeUnit.SECONDS);

        assertThat(a.size() + b.size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM integration_outbox WHERE event_id=?", Integer.class,
            eventId.toString())).isEqualTo(1);
    }

    @Test
    void staleOutboxTokenCannotCompleteAfterLeaseTakeover() {
        UUID eventId = insertOutbox("task6-stale-" + UUID.randomUUID());
        OutboxRepository.OutboxMessage old = outbox.claimBatch("task6-old", 1, Duration.ofSeconds(1)).get(0);
        jdbc.update("UPDATE integration_outbox SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE event_id=?",
            eventId.toString());
        OutboxRepository.OutboxMessage current = outbox.claimBatch("task6-new", 1, Duration.ofMinutes(1)).get(0);

        assertThat(outbox.complete(eventId, old.ownerId(), old.claimToken())).isZero();
        assertThat(outbox.complete(eventId, current.ownerId(), current.claimToken())).isEqualTo(1);
    }

    @Test
    void publisherConfirmCompletesOutboxAndLeavesUtf8EventOnRabbit() {
        UUID eventId = insertOutbox("task6-confirm-" + UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce(1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class,
            eventId.toString())).isEqualTo("PUBLISHED");
        Message message = rabbitTemplate.receive(RabbitTopology.EVENT_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
    }

    @Test
    void duplicateInboxEventIsAlreadyAcknowledgedAndProcessingIsNotCompletedEarly() {
        UUID eventId = UUID.randomUUID();
        InboxRepository.Claim first = inbox.claim("task6-consumer", eventId, Duration.ofMinutes(1)).orElseThrow();

        assertThat(inbox.claim("task6-consumer", eventId, Duration.ofMinutes(1))).isEmpty();
        assertThat(inbox.complete("task6-consumer", eventId, first.ownerId(), first.claimToken())).isEqualTo(1);
        assertThat(inbox.claim("task6-consumer", eventId, Duration.ofMinutes(1))).hasValueSatisfying(
            claim -> assertThat(claim.alreadyCompleted()).isTrue());
    }

    @Test
    void transactionRollbackDoesNotMarkInboxCompletedOrLeaveDerivedOutbox() {
        UUID eventId = UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inbox.process("task6-rollback", eventId,
            Duration.ofMinutes(1), claim -> {
                jdbc.update("""
                    INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                        payload,status,attempt_count,available_at,created_at)
                    VALUES (?,?, 'ORDER_CREATED', ?,1,1,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                    """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "task6-derived",
                    "{\"orderId\":\"task6-derived\"}");
                throw new IllegalStateException("业务事务故意回滚");
            })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, "task6-rollback", eventId.toString())).isEqualTo("PROCESSING");
    }

    private UUID insertOutbox(String aggregateId) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                payload,status,attempt_count,available_at,created_at)
            VALUES (?,?, 'ORDER_CREATED', ?,1,1,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), eventId.toString(), aggregateId,
            "{\"orderId\":\"" + aggregateId + "\"}");
        return eventId;
    }
}
