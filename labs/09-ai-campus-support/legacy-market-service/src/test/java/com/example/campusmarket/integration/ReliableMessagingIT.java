package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.messaging.InboxRepository;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.messaging.OutboxRepository;
import com.example.campusmarket.messaging.RabbitTopology;
import com.example.campusmarket.messaging.EventEnvelopeCodec;
import com.example.campusmarket.messaging.EventBusinessHandler;
import com.example.campusmarket.messaging.ReliableEventConsumer;
import com.example.campusmarket.messaging.ManualFailureScheduler;
import com.example.campusmarket.messaging.ManualFailurePublisher;
import com.example.campusmarket.shared.DomainEvent;
import com.rabbitmq.client.Channel;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(ReliableMessagingIT.ListenerFixture.class)
class ReliableMessagingIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outbox;
    @Autowired InboxRepository inbox;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired EventEnvelopeCodec codec;
    @Autowired ReliableEventConsumer consumer;
    @Autowired ManualFailureScheduler manualFailureScheduler;

    private static final AtomicReference<RuntimeException> HANDLER_FAILURE = new AtomicReference<>();
    private static final AtomicReference<Boolean> FAIL_FIRST_DELIVERY = new AtomicReference<>(false);
    private static final AtomicInteger REAL_DELIVERIES = new AtomicInteger();
    private static final AtomicInteger BUSINESS_ATTEMPTS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerFixture {
        @Bean
        EventBusinessHandler eventBusinessHandler() {
            return event -> {
                RuntimeException failure = HANDLER_FAILURE.get();
                int attempt = BUSINESS_ATTEMPTS.incrementAndGet();
                if (failure != null && (!FAIL_FIRST_DELIVERY.get() || attempt == 1)) throw failure;
            };
        }

        @Bean
        Queue task6RealDeliveryQueue() {
            return QueueBuilder.durable("task6.real.delivery").quorum().build();
        }

        @Bean
        Binding task6RealDeliveryBinding(@Qualifier("task6RealDeliveryQueue") Queue task6RealDeliveryQueue,
                                         @Qualifier("campusMarketEventExchange") DirectExchange eventExchange) {
            return BindingBuilder.bind(task6RealDeliveryQueue).to(eventExchange).with("TASK6_REAL");
        }

        @Bean
        RealDeliveryListener task6RealDeliveryListener(ReliableEventConsumer consumer) {
            return new RealDeliveryListener(consumer);
        }

        @Bean("task6RealRabbitListenerContainerFactory")
        SimpleRabbitListenerContainerFactory task6RealRabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            factory.setAutoStartup(true);
            return factory;
        }
    }

    static final class RealDeliveryListener {
        private final ReliableEventConsumer consumer;

        RealDeliveryListener(ReliableEventConsumer consumer) {
            this.consumer = consumer;
        }

        @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "task6.real.delivery",
            ackMode = "MANUAL", containerFactory = "task6RealRabbitListenerContainerFactory")
        void receive(Message message, Channel channel) throws Exception {
            REAL_DELIVERIES.incrementAndGet();
            consumer.onMessage(message, channel);
        }
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @BeforeEach
    void cleanFixtures() {
        HANDLER_FAILURE.set(null);
        FAIL_FIRST_DELIVERY.set(false);
        REAL_DELIVERIES.set(0);
        BUSINESS_ATTEMPTS.set(0);
        jdbc.update("DELETE FROM consumed_event");
        jdbc.update("DELETE FROM integration_outbox");
        jdbc.update("DELETE FROM manual_failure");
        rabbitTemplate.execute(channel -> {
            channel.queuePurge("task6.real.delivery");
            channel.queuePurge(RabbitTopology.EVENT_QUEUE);
            channel.queuePurge(RabbitTopology.MANUAL_QUEUE);
            return null;
        });
    }

    @Test
    void realConsumerAcksOnlyAfterInboxTransactionCommits() throws Exception {
        UUID eventId = UUID.randomUUID();
        Channel channel = mock(Channel.class);
        Message message = eventMessage(eventId);

        consumer.onMessage(message, channel);

        verify(channel).basicAck(0L, false);
        assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, "campus-market-order", eventId.toString())).isEqualTo("COMPLETED");
    }

    @Test
    void realConsumerNacksAndRequeuesBeforeRetryableBusinessCommit() throws Exception {
        UUID eventId = UUID.randomUUID();
        Channel channel = mock(Channel.class);
        HANDLER_FAILURE.set(new IllegalStateException("retryable test failure"));

        consumer.onMessage(eventMessage(eventId), channel);

        verify(channel).basicNack(0L, false, true);
        assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, "campus-market-order", eventId.toString())).isEqualTo("PROCESSING");
    }

    @Test
    void brokerDeliveryIsAckedAfterCommittedInboxTransaction() {
        UUID eventId = UUID.randomUUID();
        rabbitTemplate.send(RabbitTopology.EVENT_EXCHANGE, "TASK6_REAL", eventMessage(eventId));

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
                String.class, "campus-market-order", eventId.toString())).isEqualTo("COMPLETED"));
        assertThat(REAL_DELIVERIES.get()).isEqualTo(1);
    }

    @Test
    void brokerDeliveryNackRequeuesForRetryableBusinessFailure() {
        UUID eventId = UUID.randomUUID();
        HANDLER_FAILURE.set(new IllegalStateException("real broker retry"));
        FAIL_FIRST_DELIVERY.set(true);
        rabbitTemplate.send(RabbitTopology.EVENT_EXCHANGE, "TASK6_REAL", eventMessage(eventId));

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(REAL_DELIVERIES.get()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
                String.class, "campus-market-order", eventId.toString())).isEqualTo("COMPLETED");
        });
        assertThat(BUSINESS_ATTEMPTS.get()).isEqualTo(2);
        Integer readyMessages = rabbitTemplate.execute(channel -> channel.queueDeclarePassive("task6.real.delivery")
            .getMessageCount());
        assertThat(readyMessages).isZero();
        org.awaitility.Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(REAL_DELIVERIES.get()).isEqualTo(2));
    }

    @Test
    void realConsumerAcksCompletedDuplicateWithoutRunningBusinessAgain() throws Exception {
        UUID eventId = UUID.randomUUID();
        inbox.claim("campus-market-order", eventId, Duration.ofMinutes(1))
            .ifPresent(claim -> inbox.complete("campus-market-order", eventId, claim.ownerId(), claim.claimToken()));
        Channel channel = mock(Channel.class);

        consumer.onMessage(eventMessage(eventId), channel);

        verify(channel).basicAck(0L, false);
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
        assertThat(current.occurredAt()).isEqualTo(old.occurredAt());
        assertThat(codec.encode(new com.example.campusmarket.shared.DomainEvent(old.eventId(), old.eventType(),
            old.aggregateId(), old.aggregateVersion(), old.occurredAt(), old.schemaVersion(), codec.decodePayload(old.payloadJson()))))
            .isEqualTo(codec.encode(new com.example.campusmarket.shared.DomainEvent(current.eventId(), current.eventType(),
                current.aggregateId(), current.aggregateVersion(), current.occurredAt(), current.schemaVersion(),
                codec.decodePayload(current.payloadJson()))));
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
        assertThat(codec.decode(message.getBody()).eventId()).isEqualTo(eventId);
        assertThat(codec.decode(message.getBody()).occurredAt()).isEqualTo(Instant.parse("2026-08-30T01:02:03.123456Z"));
        assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
    }

    @Test
    void returnedEventIsNotMarkedPublishedWhenItsBindingIsRemoved() throws Exception {
        UUID eventId = insertOutbox("task6-unroutable-" + UUID.randomUUID());
        rabbitTemplate.execute(channel -> {
            channel.queueUnbind(RabbitTopology.EVENT_QUEUE, RabbitTopology.EVENT_EXCHANGE, "ORDER_CREATED");
            return null;
        });
        try {
            assertThat(dispatcher.dispatchOnce(1)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class,
                eventId.toString())).isEqualTo("NEW");
        } finally {
            rabbitTemplate.execute(channel -> {
                channel.queueBind(RabbitTopology.EVENT_QUEUE, RabbitTopology.EVENT_EXCHANGE, "ORDER_CREATED");
                return null;
            });
        }
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
                        occurred_at,payload,status,attempt_count,available_at,created_at)
                    VALUES (?,?, 'ORDER_CREATED', ?,1,1,?,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                    """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "task6-derived",
                    java.sql.Timestamp.from(Instant.parse("2026-08-30T01:02:03.123456Z")),
                    "{\"orderId\":\"task6-derived\"}");
                throw new IllegalStateException("业务事务故意回滚");
            })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, "task6-rollback", eventId.toString())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=?", Integer.class,
            "task6-derived")).isZero();
    }

    @Test
    void inboxLeaseTakeoverFencesOldOwnerAndRejectsSubMicrosecondLease() {
        UUID eventId = UUID.randomUUID();
        InboxRepository.Claim old = inbox.claim("task6-fence", eventId, Duration.ofSeconds(1)).orElseThrow();
        jdbc.update("UPDATE consumed_event SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE event_id=?",
            eventId.toString());
        InboxRepository.Claim current = inbox.claim("task6-fence", eventId, Duration.ofMinutes(1)).orElseThrow();

        assertThat(inbox.complete("task6-fence", eventId, old.ownerId(), old.claimToken())).isZero();
        assertThat(inbox.complete("task6-fence", eventId, current.ownerId(), current.claimToken())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inbox.claim("task6-fence-small", UUID.randomUUID(),
            Duration.ofNanos(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleInboxPermanentFailureCannotCreateManualCopy() {
        UUID eventId = UUID.randomUUID();
        InboxRepository.Claim old = inbox.claim("task6-stale-failure", eventId, Duration.ofMinutes(1)).orElseThrow();
        jdbc.update("UPDATE consumed_event SET lease_until=CURRENT_TIMESTAMP(6) - INTERVAL 1 MICROSECOND WHERE consumer_name=? AND event_id=?", "task6-stale-failure", eventId.toString());
        InboxRepository.Claim current = inbox.claim("task6-stale-failure", eventId, Duration.ofMinutes(1)).orElseThrow();

        assertThat(inbox.markFailed("task6-stale-failure", eventId, old.ownerId(), old.claimToken())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='INBOX' AND source_id=?", Integer.class, eventId.toString())).isZero();
        assertThat(inbox.complete("task6-stale-failure", eventId, current.ownerId(), current.claimToken())).isEqualTo(1);
    }

    @Test
    void outboxRetryIsBoundedAtThreeAttemptsAndThenControlledFailed() {
        UUID eventId = insertOutbox("task6-retry-" + UUID.randomUUID());
        OutboxRepository.OutboxMessage first = outbox.claimBatch("task6-retry-a", 1, Duration.ofMinutes(1)).get(0);
        outbox.releaseForRetry(eventId, first.ownerId(), first.claimToken(), Duration.ofMillis(1));
        jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6) WHERE event_id=?", eventId.toString());
        OutboxRepository.OutboxMessage second = outbox.claimBatch("task6-retry-b", 1, Duration.ofMinutes(1)).get(0);
        outbox.releaseForRetry(eventId, second.ownerId(), second.claimToken(), Duration.ofMillis(1));
        jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6) WHERE event_id=?", eventId.toString());
        OutboxRepository.OutboxMessage third = outbox.claimBatch("task6-retry-c", 1, Duration.ofMinutes(1)).get(0);

        assertThat(third.attemptCount()).isEqualTo(3);
        assertThat(outbox.fail(eventId, third.ownerId(), third.claimToken(), "EXHAUSTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class,
            eventId.toString())).isEqualTo("FAILED");
    }

    @Test
    void controlledPublisherNackAndTimeoutConvergeAfterThreeAttempts() {
        UUID eventId = insertOutbox("task6-controlled-confirm-" + UUID.randomUUID());
        AtomicInteger publishes = new AtomicInteger();
        OutboxDispatcher controlled = new OutboxDispatcher(outbox, rabbitTemplate, codec, (routingKey, message) -> {
            int attempt = publishes.incrementAndGet();
            throw new IllegalStateException(attempt == 2 ? "confirm timeout" : "publisher NACK");
        });

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(controlled.dispatchOnce(1)).isZero();
            if (attempt < 3) {
                jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6) WHERE event_id=?",
                    eventId.toString());
            }
        }

        assertThat(publishes.get()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class,
            eventId.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM integration_outbox WHERE event_id=?", String.class,
            eventId.toString())).isEqualTo("EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            Integer.class, eventId.toString())).isEqualTo(1);
    }

    @Test
    void expiredThirdOutboxLeaseCreatesManualFailureBeforeSourceFailureCompletes() {
        UUID eventId = insertOutbox("task6-third-expired-" + UUID.randomUUID());
        jdbc.update("UPDATE integration_outbox SET status='PUBLISHING', attempt_count=3, owner_id='crashed-owner', claim_token='crashed-token', lease_until=CURRENT_TIMESTAMP(6) - INTERVAL 1 MICROSECOND WHERE event_id=?", eventId.toString());

        assertThat(dispatcher.dispatchOnce(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class, eventId.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?", Integer.class, eventId.toString())).isEqualTo(1);
    }

    @Test
    void expiredThirdManualFailureLeaseConvergesToFailedAfterCrashWindow() {
        UUID sourceId = UUID.randomUUID();
        insertManualFailure(sourceId, "EXHAUSTED");
        jdbc.update("UPDATE manual_failure SET status='PUBLISHING', attempt_count=3, owner_id='crashed-owner', "
            + "claim_token='crashed-token', lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE source_id=?",
            sourceId.toString());

        assertThat(manualFailureScheduler.runOnce(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM manual_failure WHERE source_id=?", String.class,
            sourceId.toString())).isEqualTo("FAILED");
        assertThat(rabbitTemplate.receive(RabbitTopology.MANUAL_QUEUE, 100)).isNull();
    }

    @Test
    void outboxLeaseEqualToDatabaseNowIsTakenOver() {
        UUID eventId = insertOutbox("task6-equal-" + UUID.randomUUID());
        OutboxRepository.OutboxMessage old = outbox.claimBatch("task6-equal-old", 1, Duration.ofMinutes(1)).get(0);
        java.sql.Timestamp databaseNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class);
        jdbc.update("UPDATE integration_outbox SET lease_until=? WHERE event_id=?", databaseNow, eventId.toString());

        assertThat(outbox.claimBatch("task6-equal-new", 1, Duration.ofMinutes(1))).hasSize(1);
        assertThat(outbox.complete(eventId, old.ownerId(), old.claimToken())).isZero();
    }

    @Test
    void manualFailureSchedulerClaimsConcurrentlyAndPublishesOnlyOnce() throws Exception {
        UUID sourceId = UUID.randomUUID();
        insertManualFailure(sourceId, "PERMANENT");
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> manualFailureScheduler.runOnce(1));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> manualFailureScheduler.runOnce(1));

        assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM manual_failure WHERE source_id=?", String.class, sourceId.toString())).isEqualTo("PUBLISHED");
    }

    @Test
    void manualFailureStaleTokenCannotCompleteAfterTakeover() {
        UUID sourceId = UUID.randomUUID();
        insertManualFailure(sourceId, "EXHAUSTED");
        ManualFailureScheduler.ManualFailure old = manualFailureScheduler.claimBatch("task6-manual-old", 1, Duration.ofMinutes(1)).get(0);
        jdbc.update("UPDATE manual_failure SET lease_until=CURRENT_TIMESTAMP(6) - INTERVAL 1 MICROSECOND WHERE id=?", old.id().toString());
        ManualFailureScheduler.ManualFailure current = manualFailureScheduler.claimBatch("task6-manual-new", 1, Duration.ofMinutes(1)).get(0);

        assertThat(manualFailureScheduler.complete(old.id(), old.ownerId(), old.claimToken())).isZero();
        assertThat(manualFailureScheduler.complete(current.id(), current.ownerId(), current.claimToken())).isEqualTo(1);
    }

    @Test
    void persistedManualFailureIsTheOnlyNotificationFactSource() {
        UUID eventId = insertOutbox("task6-manual-single-" + UUID.randomUUID());
        jdbc.update("UPDATE integration_outbox SET event_type=? WHERE event_id=?", "NOT_A_REAL_EVENT",
            eventId.toString());

        assertThat(dispatcher.dispatchOnce(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            String.class, eventId.toString())).isEqualTo("NEW");
        assertThat(manualFailureScheduler.runOnce(1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            String.class, eventId.toString())).isEqualTo("PUBLISHED");
        assertThat(rabbitTemplate.receive(RabbitTopology.MANUAL_QUEUE, 5_000)).isNotNull();
        assertThat(rabbitTemplate.receive(RabbitTopology.MANUAL_QUEUE, 100)).isNull();
    }

    private void insertManualFailure(UUID sourceId, String failureClass) {
        jdbc.update("""
            INSERT INTO manual_failure (id,source_type,source_id,consumer_name,failure_class,payload,status,created_at)
            VALUES (?,?,?,'',?,CAST(? AS JSON),'NEW',CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), "OUTBOX", sourceId.toString(), failureClass,
            "{\"sourceId\":\"" + sourceId + "\"}");
    }

    @Test
    void successfulInboxBusinessAndCompletedShareTransactionWithDerivedOutbox() {
        UUID eventId = UUID.randomUUID();
        assertThat(inbox.process("task6-success", eventId, Duration.ofMinutes(1), claim -> jdbc.update("""
            INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                occurred_at,payload,status,attempt_count,available_at,created_at)
            VALUES (?,?, 'ORDER_CREATED', ?,1,1,?,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "task6-success-derived",
            java.sql.Timestamp.from(Instant.parse("2026-08-30T01:02:03.123456Z")),
            "{\"orderId\":\"task6-success-derived\"}"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, "task6-success", eventId.toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=?", Integer.class,
            "task6-success-derived")).isEqualTo(1);
    }

    @Test
    void invalidOutboxEventTypeReachesPermanentFailedState() {
        UUID eventId = insertOutbox("task6-bad-" + UUID.randomUUID());
        jdbc.update("UPDATE integration_outbox SET event_type=? WHERE event_id=?",
            "NOT_A_REAL_EVENT", eventId.toString());

        assertThat(dispatcher.dispatchOnce(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class,
            eventId.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM integration_outbox WHERE event_id=?", String.class,
            eventId.toString())).isEqualTo("PERMANENT");
    }

    @Test
    void manualFailureRemainsNewWhenManualNotificationIsUnroutable() throws Exception {
        UUID eventId = insertOutbox("task6-manual-unroutable-" + UUID.randomUUID());
        jdbc.update("UPDATE integration_outbox SET event_type=? WHERE event_id=?",
            "NOT_A_REAL_EVENT", eventId.toString());
        rabbitTemplate.execute(channel -> {
            channel.queueUnbind(RabbitTopology.MANUAL_QUEUE, RabbitTopology.MANUAL_EXCHANGE, "FAILURE");
            return null;
        });
        try {
            dispatcher.dispatchOnce(1);
            assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class,
                eventId.toString())).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT status FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
                String.class, eventId.toString())).isEqualTo("NEW");
        } finally {
            rabbitTemplate.execute(channel -> {
                channel.queueBind(RabbitTopology.MANUAL_QUEUE, RabbitTopology.MANUAL_EXCHANGE, "FAILURE");
                return null;
            });
        }
    }

    private UUID insertOutbox(String aggregateId) {
        UUID eventId = UUID.randomUUID();
        aggregateId = UUID.nameUUIDFromBytes(aggregateId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        jdbc.update("""
            INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                occurred_at,payload,status,attempt_count,available_at,created_at)
            VALUES (?,?, 'ORDER_CREATED', ?,1,1,?,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), eventId.toString(), aggregateId,
            java.sql.Timestamp.from(Instant.parse("2026-08-30T01:02:03.123456Z")),
            "{\"orderId\":\"" + aggregateId + "\"}");
        return eventId;
    }

    private Message eventMessage(UUID eventId) {
        DomainEvent event = new DomainEvent(eventId, "ORDER_CREATED", "task6-listener", 1,
            Instant.parse("2026-08-30T01:02:03.123456Z"), 1,
            java.util.Map.of("orderId", "task6-listener"));
        org.springframework.amqp.core.MessageProperties properties =
            new org.springframework.amqp.core.MessageProperties();
        properties.setDeliveryTag(0L);
        properties.setMessageId(eventId.toString());
        properties.setContentEncoding("UTF-8");
        return new Message(codec.encode(event), properties);
    }
}
