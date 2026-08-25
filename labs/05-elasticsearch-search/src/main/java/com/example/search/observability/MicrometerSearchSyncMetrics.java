package com.example.search.observability;

import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.OutboxStatus;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.application.sync.SearchSyncMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public class MicrometerSearchSyncMetrics implements SearchSyncMetrics {
    private final Map<IndexWriteResult.Outcome, Counter> eventCounters =
            new EnumMap<>(IndexWriteResult.Outcome.class);
    private final Map<IndexWriteResult.Outcome, Timer> bulkTimers =
            new EnumMap<>(IndexWriteResult.Outcome.class);
    private final Timer querySuccess;
    private final Timer queryFailure;
    private final Counter queryErrors;
    private final Timer rebuildSuccess;
    private final Timer rebuildFailure;
    private final Counter rebuildDifferences;

    public MicrometerSearchSyncMetrics(MeterRegistry registry, SearchOutboxRepository outbox) {
        Objects.requireNonNull(registry, "meter registry is required");
        Objects.requireNonNull(outbox, "outbox repository is required");
        for (IndexWriteResult.Outcome outcome : IndexWriteResult.Outcome.values()) {
            String value = outcome.name().toLowerCase(java.util.Locale.ROOT);
            eventCounters.put(outcome, Counter.builder("search.sync.events").tag("outcome", value).register(registry));
            bulkTimers.put(outcome, Timer.builder("search.sync.bulk.duration").tag("outcome", value).register(registry));
        }
        for (OutboxStatus status : OutboxStatus.values()) {
            Gauge.builder("search.sync.outbox", outbox, repository -> repository.countByStatus(status))
                    .tag("status", status.name()).register(registry);
        }
        Gauge.builder("search.sync.oldest.age", outbox, SearchOutboxRepository::oldestUnfinishedAgeSeconds)
                .register(registry);
        querySuccess = Timer.builder("search.query.duration").tag("outcome", "success").register(registry);
        queryFailure = Timer.builder("search.query.duration").tag("outcome", "failure").register(registry);
        queryErrors = Counter.builder("search.query.errors").register(registry);
        rebuildSuccess = Timer.builder("search.rebuild.duration").tag("outcome", "completed").register(registry);
        rebuildFailure = Timer.builder("search.rebuild.duration").tag("outcome", "failed").register(registry);
        rebuildDifferences = Counter.builder("search.rebuild.differences").register(registry);
    }

    @Override
    public void recordDispatch(IndexWriteResult.Outcome outcome, int count, Duration elapsed) {
        if (outcome == null || count < 1 || elapsed == null) return;
        eventCounters.get(outcome).increment(count);
        bulkTimers.get(outcome).record(elapsed);
    }

    @Override
    public void recordQuery(boolean success, Duration elapsed) {
        (success ? querySuccess : queryFailure).record(elapsed);
        if (!success) queryErrors.increment();
    }

    @Override
    public void recordRebuild(boolean success, Duration elapsed, long differences) {
        (success ? rebuildSuccess : rebuildFailure).record(elapsed);
        if (differences > 0) rebuildDifferences.increment(differences);
    }
}
