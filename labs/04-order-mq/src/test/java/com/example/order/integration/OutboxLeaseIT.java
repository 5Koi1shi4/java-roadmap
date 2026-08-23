package com.example.order.integration;

import com.example.order.OrderMqApplication;
import com.example.order.infrastructure.mq.OutboxEvent;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrderMqApplication.class, webEnvironment = WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxLeaseIT {
    @Autowired
    JdbcOrderRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        SharedContainers.cleanDatabase(jdbcTemplate);
        SharedContainers.purgeQueues(rabbitAdmin);
    }

    @Test
    void secondDispatcherCannotClaimFreshPublishingEvent() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        repository.insertOutbox(eventId, "NEW");

        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isFalse();
        assertThat(status(eventId)).isEqualTo("PUBLISHING");
    }

    @Test
    void expiredPublishingEventCanBeClaimedByNextDispatcher() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        repository.insertOutbox(eventId, "NEW");
        repository.forcePublishing(eventId, now.minusSeconds(1));

        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publish_attempts FROM outbox_event WHERE event_id = ?", Integer.class, eventId.toString()))
                .isEqualTo(1);
    }

    @Test
    void publishingEventAtLeaseExpiryCanBeClaimedByNextDispatcher() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        repository.insertOutbox(eventId, "NEW");
        repository.forcePublishing(eventId, now);

        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
    }

    @Test
    void batchDispatcherClaimsPublishingEventAtLeaseExpiry() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        repository.insertOutbox(eventId, "NEW");
        repository.forcePublishing(eventId, now);

        assertThat(repository.claimPublishable(now, now.plusSeconds(30), 1))
                .extracting(OutboxEvent::eventId)
                .containsExactly(eventId);
    }

    @Test
    void processingMessageAtLeaseExpiryCanBeTakenOver() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        UUID previousToken = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO consumed_message (event_id, status, lease_until, claim_token) VALUES (?, 'PROCESSING', ?, ?)",
                eventId.toString(), java.sql.Timestamp.from(now), previousToken.toString());

        JdbcOrderRepository.ConsumptionClaim claim = repository.beginConsumption(
                eventId, now, now.plusSeconds(30));

        assertThat(claim.isProcessing()).isTrue();
        assertThat(claim.claimToken()).isNotEqualTo(previousToken);
    }

    @Test
    void publishingManualFailureAtLeaseExpiryCanBeReclaimed() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        long id = repository.insertManualFailure(eventId, "{}", "RETRYABLE", "failed", 3, now);
        jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = 'PUBLISHING', attempts = 1, lease_until = ? WHERE id = ?",
                java.sql.Timestamp.from(now), id);

        List<JdbcOrderRepository.ManualFailure> claimed = repository.claimManualFailures(
                now, now.plusSeconds(30), 1);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).id()).isEqualTo(id);
        assertThat(claimed.get(0).attempts()).isEqualTo(2);
    }

    @Test
    void exhaustedPublishingManualFailureAtLeaseExpiryIsGivenUp() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        long id = repository.insertManualFailure(eventId, "{}", "RETRYABLE", "failed", 3, now);
        jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = 'PUBLISHING', attempts = 3, lease_until = ? WHERE id = ?",
                java.sql.Timestamp.from(now), id);

        assertThat(repository.claimManualFailures(now, now.plusSeconds(30), 1)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT manual_delivery_status FROM manual_failure WHERE id = ?", String.class, id))
                .isEqualTo("GIVE_UP");
    }

    @Test
    void lateAckFromPreviousOwnerCannotCompleteNewLease() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        repository.insertOutbox(eventId, "NEW");
        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
        UUID firstToken = repository.claimToken(eventId);

        repository.forcePublishing(eventId, now.minusSeconds(1));
        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
        UUID secondToken = repository.claimToken(eventId);

        assertThat(repository.markPublished(eventId, firstToken, now)).isZero();
        assertThat(status(eventId)).isEqualTo("PUBLISHING");
        assertThat(repository.markPublished(eventId, secondToken, now)).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    void lateNackFromPreviousOwnerCannotReleaseNewLease() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        repository.insertOutbox(eventId, "NEW");
        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
        UUID firstToken = repository.claimToken(eventId);

        repository.forcePublishing(eventId, now.minusSeconds(1));
        assertThat(repository.claim(eventId, now, now.plusSeconds(30))).isTrue();
        UUID secondToken = repository.claimToken(eventId);

        assertThat(repository.releaseForRetry(eventId, firstToken, "AMQP", "late nack")).isZero();
        assertThat(status(eventId)).isEqualTo("PUBLISHING");
        assertThat(repository.releaseForRetry(eventId, secondToken, "AMQP", "current nack")).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("NEW");
    }

    @Test
    void eachDispatchClaimsNoMoreThanFiftyRows() {
        for (int i = 0; i < 51; i++) {
            repository.insertOutbox(UUID.randomUUID(), "NEW");
        }

        List<OutboxEvent> claimed = repository.claimPublishable(
                Instant.now(), Instant.now().plusSeconds(30), 500);

        assertThat(claimed).hasSize(50);
    }

    private String status(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId.toString());
    }
}
