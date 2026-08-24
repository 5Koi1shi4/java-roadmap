package com.example.search.unit;

import com.example.search.application.sync.ClaimedOutboxEvent;
import com.example.search.application.sync.DispatchSummary;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.OutboxClaimService;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchSyncMetrics;
import com.example.search.domain.ProductSearchSnapshot;
import com.example.search.domain.ProductStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {
    private final OutboxClaimService claims = mock(OutboxClaimService.class);
    private final SearchIndexWriter writer = mock(SearchIndexWriter.class);
    private final SearchSyncMetrics metrics = mock(SearchSyncMetrics.class);

    @Test
    void completesSuccessfulItemsAndRetriesOnlyTransientFailures() {
        ClaimedOutboxEvent eventA = event(1, 1);
        ClaimedOutboxEvent eventB = event(2, 1);
        ClaimedOutboxEvent eventC = event(3, 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(eventA, eventB, eventC));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(List.of(
                result(eventA, IndexWriteResult.Outcome.APPLIED, "ok"),
                result(eventB, IndexWriteResult.Outcome.RETRYABLE_FAILURE, "429\nretry"),
                result(eventC, IndexWriteResult.Outcome.PERMANENT_FAILURE, "mapping")));
        when(claims.complete(eventA.eventId(), eventA.claimToken())).thenReturn(true);
        when(claims.reschedule(eventB.eventId(), eventB.claimToken(), Duration.ofSeconds(1), "429retry"))
                .thenReturn(true);
        when(claims.fail(eventC.eventId(), eventC.claimToken(), "mapping")).thenReturn(true);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, metrics, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(3, 1, 1, 1, 0));
        verify(claims).complete(eventA.eventId(), eventA.claimToken());
        verify(claims).reschedule(eventB.eventId(), eventB.claimToken(), Duration.ofSeconds(1), "429retry");
        verify(claims).fail(eventC.eventId(), eventC.claimToken(), "mapping");
    }

    @Test
    void recordsFencingAndNeverTriesASecondTokenUpdate() {
        ClaimedOutboxEvent event = event(4, 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(event));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(
                List.of(result(event, IndexWriteResult.Outcome.APPLIED, "ok")));
        when(claims.complete(event.eventId(), event.claimToken())).thenReturn(false);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, metrics, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 0, 0, 0, 1));
    }

    @Test
    void failsTheFifthAttemptInsteadOfRescheduling() {
        ClaimedOutboxEvent event = event(5, 5);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(event));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(
                List.of(result(event, IndexWriteResult.Outcome.RETRYABLE_FAILURE, "503")));
        when(claims.fail(event.eventId(), event.claimToken(), "503")).thenReturn(true);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, metrics, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 0, 0, 1, 0));
        verify(claims).fail(event.eventId(), event.claimToken(), "503");
    }

    @Test
    void exposesFixedRetryScheduleWithFifthAttemptTerminal() {
        var schedule = new com.example.search.application.sync.RetrySchedule();
        assertThat(schedule.delayAfterFailure(1)).contains(Duration.ofSeconds(1));
        assertThat(schedule.delayAfterFailure(2)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayAfterFailure(3)).contains(Duration.ofSeconds(30));
        assertThat(schedule.delayAfterFailure(4)).contains(Duration.ofMinutes(2));
        assertThat(schedule.delayAfterFailure(5)).isEmpty();
    }

    private static IndexWriteResult result(ClaimedOutboxEvent event, IndexWriteResult.Outcome outcome,
                                           String message) {
        return new IndexWriteResult(event.productId(), event.productVersion(), outcome, message);
    }

    private static ClaimedOutboxEvent event(long id, int attempt) {
        UUID eventId = UUID.randomUUID();
        return new ClaimedOutboxEvent(id, eventId, id, 1, OutboxEventType.PRODUCT_UPSERT,
                new ProductSearchSnapshot(id, 1, "商品" + id, null, "描述", "BOOK", "图书",
                        new BigDecimal("10.00"), ProductStatus.ON_SALE, Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z")), attempt, "test-node", UUID.randomUUID(),
                Instant.parse("2026-01-01T00:01:00Z"));
    }
}
