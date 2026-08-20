package com.example.cache.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

public final class MicrometerCacheMetrics implements CacheMetrics {

    private final Counter hits;
    private final Counter misses;
    private final Counter negativeHits;
    private final Counter repositoryLoads;
    private final Counter lockBusy;
    private final Timer lockWait;

    public MicrometerCacheMetrics(MeterRegistry registry) {
        hits = registry.counter("cache.hit");
        misses = registry.counter("cache.miss");
        negativeHits = registry.counter("cache.negative_hit");
        repositoryLoads = registry.counter("cache.repository_load");
        lockBusy = registry.counter("cache.lock_busy");
        lockWait = registry.timer("cache.lock.wait");
    }

    @Override
    public void recordHit() {
        hits.increment();
    }

    @Override
    public void recordMiss() {
        misses.increment();
    }

    @Override
    public void recordNegativeHit() {
        negativeHits.increment();
    }

    @Override
    public void recordRepositoryLoad() {
        repositoryLoads.increment();
    }

    @Override
    public void recordLockBusy() {
        lockBusy.increment();
    }

    @Override
    public void recordLockWait(Duration duration) {
        lockWait.record(duration);
    }
}
