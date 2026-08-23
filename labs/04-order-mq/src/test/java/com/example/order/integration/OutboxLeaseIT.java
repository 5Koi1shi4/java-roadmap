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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = OrderMqApplication.class, webEnvironment = WebEnvironment.NONE)
class OutboxLeaseIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("test");

    @Autowired
    JdbcOrderRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_event");
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
