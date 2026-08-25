package com.example.search.application.sync;

import com.example.search.config.SearchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Delivers claimed outbox rows while fencing every result with its claim token. */
@Service
public class OutboxDispatcher {
    private static final int DEFAULT_CLAIM_LIMIT = 50;
    private static final String TARGET = "products-write";

    private final OutboxClaimService claims;
    private final SearchIndexWriter writer;
    private final RetrySchedule retrySchedule;
    private final SyncFailureClassifier failureClassifier;
    private final SearchSyncMetrics metrics;
    private final String owner;
    private final int claimLimit;

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(), new NoopMetrics(),
                "search-dispatcher-" + UUID.randomUUID(), DEFAULT_CLAIM_LIMIT);
    }

    @Autowired
    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            ObjectProvider<SearchSyncMetrics> metricsProvider, SearchProperties properties) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(),
                resolveMetrics(metricsProvider), "search-dispatcher-" + UUID.randomUUID(), properties.batchSize());
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            ObjectProvider<SearchSyncMetrics> metricsProvider) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(),
                resolveMetrics(metricsProvider), "search-dispatcher-" + UUID.randomUUID(), DEFAULT_CLAIM_LIMIT);
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            ObjectProvider<SearchSyncMetrics> metricsProvider, String owner) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(),
                resolveMetrics(metricsProvider), owner, DEFAULT_CLAIM_LIMIT);
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer, SearchSyncMetrics metrics) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(), metrics,
                "search-dispatcher-" + UUID.randomUUID(), DEFAULT_CLAIM_LIMIT);
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            SearchSyncMetrics metrics, String owner) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(), metrics, owner, DEFAULT_CLAIM_LIMIT);
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            SearchSyncMetrics metrics, String owner, int claimLimit) {
        this(claims, writer, new RetrySchedule(), new SyncFailureClassifier(), metrics, owner, claimLimit);
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            RetrySchedule retrySchedule, SyncFailureClassifier failureClassifier,
                            SearchSyncMetrics metrics, String owner) {
        this(claims, writer, retrySchedule, failureClassifier, metrics, owner, DEFAULT_CLAIM_LIMIT);
    }

    public OutboxDispatcher(OutboxClaimService claims, SearchIndexWriter writer,
                            RetrySchedule retrySchedule, SyncFailureClassifier failureClassifier,
                            SearchSyncMetrics metrics, String owner, int claimLimit) {
        this.claims = Objects.requireNonNull(claims, "claims is required");
        this.writer = Objects.requireNonNull(writer, "writer is required");
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule is required");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1 to 128 characters");
        }
        this.owner = owner;
        if (claimLimit < 1 || claimLimit > 50) {
            throw new IllegalArgumentException("claim limit must be between 1 and 50");
        }
        this.claimLimit = claimLimit;
    }

    public DispatchSummary dispatchOnce() {
        Instant started = Instant.now();
        List<ClaimedOutboxEvent> claimed = claims.claim(owner, claimLimit);
        if (claimed.isEmpty()) {
            return new DispatchSummary(0, 0, 0, 0, 0);
        }

        Map<MutationKey, ArrayDeque<IndexWriteResult>> writes = new HashMap<>();
        Map<MutationKey, String> conversionFailures = new HashMap<>();
        Map<MutationKey, Integer> keyCounts = new HashMap<>();
        for (ClaimedOutboxEvent event : claimed) {
            keyCounts.merge(new MutationKey(event.productId(), event.productVersion()), 1, Integer::sum);
        }
        List<IndexMutation> mutations = new ArrayList<>();
        for (ClaimedOutboxEvent event : claimed) {
            MutationKey key = new MutationKey(event.productId(), event.productVersion());
            if (keyCounts.get(key) > 1) {
                continue;
            }
            try {
                mutations.add(IndexMutation.from(event.snapshot()));
            } catch (RuntimeException exception) {
                conversionFailures.put(key, failureClassifier.reason(exception));
            }
        }

        if (!mutations.isEmpty()) {
            try {
                List<IndexWriteResult> results = writer.bulkWrite(TARGET, mutations);
                if (results == null) {
                    throw new IllegalStateException("bulk writer returned null results");
                }
                for (IndexWriteResult result : results) {
                    if (result == null || result.outcome() == null) continue;
                    writes.computeIfAbsent(new MutationKey(result.productId(), result.sourceVersion()),
                            ignored -> new ArrayDeque<>()).add(result);
                }
            } catch (Exception exception) {
                IndexWriteResult.Outcome outcome = failureClassifier.classify(exception);
                for (IndexMutation mutation : mutations) {
                    MutationKey key = new MutationKey(mutation.productId(), mutation.sourceVersion());
                    writes.computeIfAbsent(key, ignored -> new ArrayDeque<>())
                            .add(new IndexWriteResult(mutation.productId(), mutation.sourceVersion(), outcome,
                                    failureClassifier.reason(exception)));
                }
            }
        }

        Counters counters = new Counters();
        for (ClaimedOutboxEvent event : claimed) {
            MutationKey key = new MutationKey(event.productId(), event.productVersion());
            String conversionFailure = keyCounts.get(key) > 1
                    ? "ambiguous product version in claimed outbox batch"
                    : conversionFailures.get(key);
            IndexWriteResult result = conversionFailure == null && writes.containsKey(key)
                    ? writes.get(key).pollFirst()
                    : null;
            if (result == null) {
                result = new IndexWriteResult(event.productId(), event.productVersion(),
                        IndexWriteResult.Outcome.PERMANENT_FAILURE,
                        conversionFailure == null ? "bulk writer returned no item result" : conversionFailure);
            }
            applyResult(event, result, counters);
        }

        Duration elapsed = Duration.between(started, Instant.now());
        counters.recordMetrics(metrics, elapsed);
        return new DispatchSummary(claimed.size(), counters.completed, counters.rescheduled,
                counters.failed, counters.fenced);
    }

    private static SearchSyncMetrics resolveMetrics(ObjectProvider<SearchSyncMetrics> provider) {
        if (provider == null) return new NoopMetrics();
        SearchSyncMetrics metrics = provider.getIfAvailable();
        return metrics == null ? new NoopMetrics() : metrics;
    }

    private void applyResult(ClaimedOutboxEvent event, IndexWriteResult result, Counters counters) {
        counters.outcomes.merge(result.outcome(), 1, Integer::sum);
        String reason = failureClassifier.sanitizeReason(result.message());
        boolean changed;
        switch (result.outcome()) {
            case APPLIED, SUPERSEDED -> {
                changed = claims.complete(event.eventId(), event.claimToken());
                if (changed) counters.completed++;
                else counters.fenced++;
            }
            case RETRYABLE_FAILURE -> {
                if (event.attemptCount() < 5) {
                    Duration delay = retrySchedule.delayAfterFailure(event.attemptCount()).orElse(Duration.ZERO);
                    changed = claims.reschedule(event.eventId(), event.claimToken(), delay,
                            reason == null ? "transient index write failure" : reason);
                    if (changed) counters.rescheduled++;
                    else counters.fenced++;
                } else {
                    changed = claims.fail(event.eventId(), event.claimToken(),
                            reason == null ? "retry limit exceeded" : reason);
                    if (changed) counters.failed++;
                    else counters.fenced++;
                }
            }
            case PERMANENT_FAILURE -> {
                changed = claims.fail(event.eventId(), event.claimToken(),
                        reason == null ? "permanent index write failure" : reason);
                if (changed) counters.failed++;
                else counters.fenced++;
            }
            default -> throw new IllegalStateException("unsupported write outcome: " + result.outcome());
        }
    }

    private record MutationKey(long productId, long sourceVersion) {
    }

    private static final class Counters {
        private final EnumMap<IndexWriteResult.Outcome, Integer> outcomes =
                new EnumMap<>(IndexWriteResult.Outcome.class);
        private int completed;
        private int rescheduled;
        private int failed;
        private int fenced;

        private void recordMetrics(SearchSyncMetrics metrics, Duration elapsed) {
            outcomes.forEach((outcome, count) -> metrics.recordDispatch(outcome, count, elapsed));
        }
    }

    private static final class NoopMetrics implements SearchSyncMetrics {
        @Override public void recordDispatch(IndexWriteResult.Outcome outcome, int count, Duration elapsed) {
        }

        @Override public void recordQuery(boolean success, Duration elapsed) {
        }

        @Override public void recordRebuild(boolean success, Duration elapsed, long differences) {
        }
    }
}
