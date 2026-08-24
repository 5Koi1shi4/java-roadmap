package com.example.search.unit;

import com.example.search.application.sync.ClaimedOutboxEvent;
import com.example.search.application.sync.DispatchSummary;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.OutboxClaimService;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchSyncMetrics;
import com.example.search.application.sync.SyncFailureClassifier;
import com.example.search.domain.ProductSearchSnapshot;
import com.example.search.domain.ProductStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

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

    @Test
    void permanentlyFailsSameProductVersionWithDifferentEventTypesWithoutWritingEither() {
        ClaimedOutboxEvent upsert = event(6, 1, OutboxEventType.PRODUCT_UPSERT);
        ClaimedOutboxEvent delete = event(6, 1, OutboxEventType.PRODUCT_DELETE);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(upsert, delete));
        when(claims.fail(eq(upsert.eventId()), eq(upsert.claimToken()), anyString())).thenReturn(true);
        when(claims.fail(eq(delete.eventId()), eq(delete.claimToken()), anyString())).thenReturn(true);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, metrics, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(2, 0, 0, 2, 0));
        org.mockito.Mockito.verifyNoInteractions(writer);
        verify(claims).fail(eq(upsert.eventId()), eq(upsert.claimToken()), anyString());
        verify(claims).fail(eq(delete.eventId()), eq(delete.claimToken()), anyString());
    }

    @Test
    void mapsOutOfOrderAndDuplicateResultsByVersionAndFailsMissingItems() {
        ClaimedOutboxEvent first = event(7, 1);
        ClaimedOutboxEvent second = event(8, 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(first, second));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(List.of(
                result(second, IndexWriteResult.Outcome.APPLIED, "second"),
                result(second, IndexWriteResult.Outcome.APPLIED, "duplicate")));
        when(claims.fail(first.eventId(), first.claimToken(), "bulk writer returned no item result")).thenReturn(true);
        when(claims.complete(second.eventId(), second.claimToken())).thenReturn(true);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, metrics, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(2, 1, 0, 1, 0));
        verify(claims).fail(first.eventId(), first.claimToken(), "bulk writer returned no item result");
        verify(claims).complete(second.eventId(), second.claimToken());
    }

    @Test
    void usesProvidedMetricsBeanThroughOptionalProductionWiring() {
        ObjectProvider<SearchSyncMetrics> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(metrics);
        ClaimedOutboxEvent event = event(13, 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(event));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(
                List.of(result(event, IndexWriteResult.Outcome.APPLIED, "ok")));
        when(claims.complete(event.eventId(), event.claimToken())).thenReturn(true);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, provider, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 1, 0, 0, 0));
        verify(provider).getIfAvailable();
        verify(metrics).recordDispatch(eq(IndexWriteResult.Outcome.APPLIED), eq(1), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void classifiesNetworkAndPayloadFailuresWithoutTreatingTimeoutDurationAsHttpStatus() {
        SyncFailureClassifier classifier = new SyncFailureClassifier();
        assertThat(classifier.classify(new java.net.ConnectException("connection refused")))
                .isEqualTo(IndexWriteResult.Outcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(new java.io.IOException("timed out after 200ms")))
                .isEqualTo(IndexWriteResult.Outcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(new RuntimeException("HTTP status 429")))
                .isEqualTo(IndexWriteResult.Outcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(new RuntimeException("response status: 503")))
                .isEqualTo(IndexWriteResult.Outcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(new JsonProcessingException("serialization failed") { }))
                .isEqualTo(IndexWriteResult.Outcome.PERMANENT_FAILURE);
        assertThat(classifier.classify(new RuntimeException("strict_dynamic_mapping_exception")))
                .isEqualTo(IndexWriteResult.Outcome.PERMANENT_FAILURE);
        assertThat(classifier.classify(new IllegalArgumentException("illegal snapshot")))
                .isEqualTo(IndexWriteResult.Outcome.PERMANENT_FAILURE);
    }

    @Test
    void countsFencingOnceForEveryWriteOutcome() {
        ClaimedOutboxEvent applied = event(9, 1);
        ClaimedOutboxEvent superseded = event(10, 1);
        ClaimedOutboxEvent retryable = event(11, 1);
        ClaimedOutboxEvent permanent = event(12, 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(applied, superseded, retryable, permanent));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(List.of(
                result(applied, IndexWriteResult.Outcome.APPLIED, "applied"),
                result(superseded, IndexWriteResult.Outcome.SUPERSEDED, "old"),
                result(retryable, IndexWriteResult.Outcome.RETRYABLE_FAILURE, "429"),
                result(permanent, IndexWriteResult.Outcome.PERMANENT_FAILURE, "mapping")));
        when(claims.complete(applied.eventId(), applied.claimToken())).thenReturn(false);
        when(claims.complete(superseded.eventId(), superseded.claimToken())).thenReturn(false);
        when(claims.reschedule(retryable.eventId(), retryable.claimToken(), Duration.ofSeconds(1), "429"))
                .thenReturn(false);
        when(claims.fail(permanent.eventId(), permanent.claimToken(), "mapping")).thenReturn(false);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, writer, metrics, "test-node");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(4, 0, 0, 0, 4));
        verify(claims, times(1)).complete(applied.eventId(), applied.claimToken());
        verify(claims, times(1)).complete(superseded.eventId(), superseded.claimToken());
        verify(claims, times(1)).reschedule(retryable.eventId(), retryable.claimToken(), Duration.ofSeconds(1), "429");
        verify(claims, times(1)).fail(permanent.eventId(), permanent.claimToken(), "mapping");
        verify(claims, never()).fail(applied.eventId(), applied.claimToken(), "mapping");
    }

    @ParameterizedTest
    @EnumSource(IndexWriteResult.Outcome.class)
    void fencesEachOutcomeWithExactlyOneConditionalUpdate(IndexWriteResult.Outcome outcome) {
        ClaimedOutboxEvent event = event(20 + outcome.ordinal(), 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(event));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(List.of(result(event, outcome, "reason")));
        if (outcome == IndexWriteResult.Outcome.APPLIED || outcome == IndexWriteResult.Outcome.SUPERSEDED) {
            when(claims.complete(event.eventId(), event.claimToken())).thenReturn(false);
        } else if (outcome == IndexWriteResult.Outcome.RETRYABLE_FAILURE) {
            when(claims.reschedule(event.eventId(), event.claimToken(), Duration.ofSeconds(1), "reason"))
                    .thenReturn(false);
        } else {
            when(claims.fail(event.eventId(), event.claimToken(), "reason")).thenReturn(false);
        }

        assertThat(new OutboxDispatcher(claims, writer, metrics, "test-node").dispatchOnce())
                .isEqualTo(new DispatchSummary(1, 0, 0, 0, 1));
        if (outcome == IndexWriteResult.Outcome.APPLIED || outcome == IndexWriteResult.Outcome.SUPERSEDED) {
            verify(claims, times(1)).complete(event.eventId(), event.claimToken());
        } else if (outcome == IndexWriteResult.Outcome.RETRYABLE_FAILURE) {
            verify(claims, times(1)).reschedule(event.eventId(), event.claimToken(), Duration.ofSeconds(1), "reason");
        } else {
            verify(claims, times(1)).fail(event.eventId(), event.claimToken(), "reason");
        }
    }

    @Test
    void classifiesBatchConnectionFailureAndReschedulesEveryClaimedItem() {
        ClaimedOutboxEvent first = event(30, 1);
        ClaimedOutboxEvent second = event(31, 1);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(first, second));
        when(writer.bulkWrite(eq("products-write"), anyList()))
                .thenThrow(new RuntimeException("connection refused"));
        when(claims.reschedule(first.eventId(), first.claimToken(), Duration.ofSeconds(1), "connection refused"))
                .thenReturn(true);
        when(claims.reschedule(second.eventId(), second.claimToken(), Duration.ofSeconds(1), "connection refused"))
                .thenReturn(true);

        assertThat(new OutboxDispatcher(claims, writer, metrics, "test-node").dispatchOnce())
                .isEqualTo(new DispatchSummary(2, 0, 2, 0, 0));
        verify(claims).reschedule(first.eventId(), first.claimToken(), Duration.ofSeconds(1), "connection refused");
        verify(claims).reschedule(second.eventId(), second.claimToken(), Duration.ofSeconds(1), "connection refused");
    }

    @Test
    void cleansAndTruncatesFailureReasonBeforeConditionalUpdate() {
        ClaimedOutboxEvent event = event(32, 1);
        String rawReason = "x\n\r".repeat(1200);
        when(claims.claim(anyString(), eq(50))).thenReturn(List.of(event));
        when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(
                List.of(result(event, IndexWriteResult.Outcome.PERMANENT_FAILURE, rawReason)));
        when(claims.fail(eq(event.eventId()), eq(event.claimToken()), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        assertThat(new OutboxDispatcher(claims, writer, metrics, "test-node").dispatchOnce())
                .isEqualTo(new DispatchSummary(1, 0, 0, 1, 0));
        var captured = forClass(String.class);
        verify(claims).fail(eq(event.eventId()), eq(event.claimToken()), captured.capture());
        assertThat(captured.getValue()).hasSize(1024).doesNotContain("\n", "\r");
    }

    private static IndexWriteResult result(ClaimedOutboxEvent event, IndexWriteResult.Outcome outcome,
                                           String message) {
        return new IndexWriteResult(event.productId(), event.productVersion(), outcome, message);
    }

    private static ClaimedOutboxEvent event(long id, int attempt) {
        return event(id, attempt, OutboxEventType.PRODUCT_UPSERT);
    }

    private static ClaimedOutboxEvent event(long id, int attempt, OutboxEventType eventType) {
        UUID eventId = UUID.randomUUID();
        return new ClaimedOutboxEvent(id, eventId, id, 1, eventType,
                new ProductSearchSnapshot(id, 1, "商品" + id, null, "描述", "BOOK", "图书",
                        new BigDecimal("10.00"), ProductStatus.ON_SALE, Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z")), attempt, "test-node", UUID.randomUUID(),
                Instant.parse("2026-01-01T00:01:00Z"));
    }
}
