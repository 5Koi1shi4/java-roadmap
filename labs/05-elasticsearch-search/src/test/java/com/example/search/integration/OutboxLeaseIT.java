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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxLeaseIT extends SharedMySqlContainer {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
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
    void pausedDispatcherDoesNotClaimRows() {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));
        jdbc.update("UPDATE search_coordination SET dispatcher_paused = TRUE WHERE id = 1");

        assertThat(outbox.claim("node-a", 1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox LIMIT 1", String.class)).isEqualTo("NEW");
    }

    @Test
    void oldTokenCannotCompleteRescheduleOrFail() {
        products.create(new CreateProductCommand(new ProductDetails(
                "书", null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE)));
        ClaimedOutboxEvent first = outbox.claim("node-a", 1).get(0);
        assertThat(outbox.reschedule(first.eventId(), first.claimToken(), Duration.ZERO, "try again")).isTrue();
        ClaimedOutboxEvent second = outbox.claim("node-b", 1).get(0);

        assertThat(outbox.complete(first.eventId(), first.claimToken())).isFalse();
        assertThat(outbox.reschedule(first.eventId(), first.claimToken(), Duration.ZERO, "late")).isFalse();
        assertThat(outbox.fail(first.eventId(), first.claimToken(), "late")).isFalse();
        assertThat(outbox.fail(second.eventId(), second.claimToken(), "done")).isTrue();
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
}
