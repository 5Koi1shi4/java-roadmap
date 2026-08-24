package com.example.search.integration;

import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.sync.ClaimedOutboxEvent;
import com.example.search.application.sync.OutboxClaimService;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxLeaseIT extends SharedMySqlContainer {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ProductCommandService products;
    @Autowired OutboxClaimService outbox;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
    }

    @Test
    void claimsEachEventOnceAndRejectsLateOwner() {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));

        List<ClaimedOutboxEvent> first = outbox.claim("node-a", 50);
        assertThat(first).hasSize(1);
        ClaimedOutboxEvent firstClaim = first.get(0);

        jdbc.update("UPDATE search_outbox SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MICROSECOND) WHERE event_id = ?",
                firstClaim.eventId().toString());
        List<ClaimedOutboxEvent> second = outbox.claim("node-b", 50);

        assertThat(second).singleElement().satisfies(secondClaim -> {
            assertThat(secondClaim.eventId()).isEqualTo(firstClaim.eventId());
            assertThat(secondClaim.claimToken()).isNotEqualTo(firstClaim.claimToken());
        });
        assertThat(outbox.complete(firstClaim.eventId(), firstClaim.claimToken())).isFalse();
        assertThat(outbox.complete(second.get(0).eventId(), second.get(0).claimToken())).isTrue();
    }

    @Test
    void newlyAppendedOutboxIsAvailableAccordingToDatabaseClock() {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));

        Map<String, Object> timing = jdbc.queryForMap(
                "SELECT available_at, UTC_TIMESTAMP(6) AS db_now, @@session.time_zone AS session_time_zone, "
                        + "TIMESTAMPDIFF(MICROSECOND, available_at, UTC_TIMESTAMP(6)) AS delta_micros, "
                        + "(available_at <= UTC_TIMESTAMP(6)) AS available_now "
                        + "FROM search_outbox ORDER BY id DESC LIMIT 1");
        String evidence = "available_at=" + timing.get("available_at")
                + ", db_now=" + timing.get("db_now")
                + ", session_time_zone=" + timing.get("session_time_zone")
                + ", delta_micros=" + timing.get("delta_micros");

        assertThat(((Number) timing.get("available_now")).intValue()).as(evidence).isEqualTo(1);
        assertThat(((Number) timing.get("delta_micros")).longValue()).as(evidence).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void twoIndependentTransactionsClaimDisjointEventSets() throws Exception {
        for (int i = 0; i < 4; i++) {
            products.create(new CreateProductCommand(new ProductDetails(
                    "书" + i, null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));
        }

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedOutboxEvent>> first = executor.submit(() -> claimAfter(start, "node-a", 2));
            Future<List<ClaimedOutboxEvent>> second = executor.submit(() -> claimAfter(start, "node-b", 2));

            List<ClaimedOutboxEvent> firstClaims = first.get(5, TimeUnit.SECONDS);
            List<ClaimedOutboxEvent> secondClaims = second.get(5, TimeUnit.SECONDS);
            assertThat(first.isDone()).isTrue();
            assertThat(second.isDone()).isTrue();
            assertThat(firstClaims).hasSize(2);
            assertThat(secondClaims).hasSize(2);

            Set<UUID> firstIds = firstClaims.stream().map(ClaimedOutboxEvent::eventId).collect(Collectors.toSet());
            Set<UUID> secondIds = secondClaims.stream().map(ClaimedOutboxEvent::eventId).collect(Collectors.toSet());
            Set<UUID> union = new HashSet<>(firstIds);
            union.addAll(secondIds);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            assertThat(union).hasSize(4);
            assertClaimMetadata(firstClaims, "node-a");
            assertClaimMetadata(secondClaims, "node-b");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void pausedDispatcherDoesNotClaimRows() {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));
        jdbc.update("UPDATE search_coordination SET dispatcher_paused = TRUE WHERE id = 1");

        assertThat(outbox.claim("node-a", 1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox LIMIT 1", String.class)).isEqualTo("NEW");
    }

    @Test
    void oldTokenCannotCompleteRescheduleOrFailConcurrentlyAfterTakeover() throws Exception {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));
        ClaimedOutboxEvent first = outbox.claim("node-a", 1).get(0);
        jdbc.update("UPDATE search_outbox SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MICROSECOND) WHERE event_id = ?",
                first.eventId().toString());
        ClaimedOutboxEvent second = outbox.claim("node-b", 1).get(0);

        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> complete = executor.submit(() -> staleCompleteAfter(start, first));
            Future<Boolean> reschedule = executor.submit(() -> staleRescheduleAfter(start, first));
            Future<Boolean> fail = executor.submit(() -> staleFailAfter(start, first));

            assertThat(complete.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(reschedule.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(fail.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(complete.isDone()).isTrue();
            assertThat(reschedule.isDone()).isTrue();
            assertThat(fail.isDone()).isTrue();
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> current = jdbc.queryForMap(
                "SELECT status, owner, claim_token, lease_until FROM search_outbox WHERE event_id=?",
                second.eventId().toString());
        assertThat(current.get("status")).isEqualTo("PROCESSING");
        assertThat(current.get("owner")).isEqualTo("node-b");
        assertThat(current.get("claim_token")).isEqualTo(second.claimToken().toString());
        assertThat(current.get("lease_until")).isNotNull();
        assertThat(outbox.fail(second.eventId(), second.claimToken(), "done")).isTrue();
        Map<String, Object> finalState = jdbc.queryForMap(
                "SELECT status, owner, claim_token, lease_until FROM search_outbox WHERE event_id=?",
                second.eventId().toString());
        assertThat(finalState.get("status")).isEqualTo("FAILED");
        assertThat(finalState.get("owner")).isNull();
        assertThat(finalState.get("claim_token")).isNull();
        assertThat(finalState.get("lease_until")).isNull();
    }

    @Test
    void failureReasonIsSingleLineAndBounded() {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));
        ClaimedOutboxEvent claim = outbox.claim("node-a", 1).get(0);
        String reason = IntStream.range(0, 1_100).mapToObj(i -> "x\n").collect(Collectors.joining());

        assertThat(outbox.fail(claim.eventId(), claim.claimToken(), reason)).isTrue();
        String stored = jdbc.queryForObject("SELECT last_error FROM search_outbox WHERE event_id=?", String.class,
                claim.eventId().toString());
        assertThat(stored).hasSize(1024).doesNotContain("\n", "\r");
    }

    private List<ClaimedOutboxEvent> claimAfter(CyclicBarrier start, String owner, int limit) {
        return transactionTemplate.execute(status -> {
            await(start);
            return outbox.claim(owner, limit);
        });
    }

    private Boolean staleCompleteAfter(CyclicBarrier start, ClaimedOutboxEvent stale) {
        return transactionTemplate.execute(status -> {
            await(start);
            return outbox.complete(stale.eventId(), stale.claimToken());
        });
    }

    private Boolean staleRescheduleAfter(CyclicBarrier start, ClaimedOutboxEvent stale) {
        return transactionTemplate.execute(status -> {
            await(start);
            return outbox.reschedule(stale.eventId(), stale.claimToken(), Duration.ZERO, "late");
        });
    }

    private Boolean staleFailAfter(CyclicBarrier start, ClaimedOutboxEvent stale) {
        return transactionTemplate.execute(status -> {
            await(start);
            return outbox.fail(stale.eventId(), stale.claimToken(), "late");
        });
    }

    private void assertClaimMetadata(List<ClaimedOutboxEvent> claims, String owner) {
        assertThat(claims).allSatisfy(claim -> {
            assertThat(claim.owner()).isEqualTo(owner);
            assertThat(claim.claimToken()).isNotNull();
            assertThat(claim.attemptCount()).isEqualTo(1);
            assertThat(claim.leaseUntil()).isAfter(Instant.now());
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
