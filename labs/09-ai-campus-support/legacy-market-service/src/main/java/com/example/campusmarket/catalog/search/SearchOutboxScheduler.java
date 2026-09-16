package com.example.campusmarket.catalog.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** outbox worker 的生产入口；除非显式启用，否则保持禁用。 */
@Component
@Profile("!test")
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.search.dispatcher", name = "enabled", havingValue = "true")
public final class SearchOutboxScheduler {
    private final SearchOutboxDispatcher dispatcher;
    private final SearchIndexCleanupWorker cleanupWorker;
    private final SearchRebuildReconciler reconciler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SearchOutboxScheduler(SearchOutboxDispatcher dispatcher, SearchIndexCleanupWorker cleanupWorker,
                                 SearchRebuildReconciler reconciler) {
        this.dispatcher = dispatcher;
        this.cleanupWorker = cleanupWorker;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${campus.market.search.dispatcher.fixed-delay-ms:1000}")
    public void dispatch() {
        if (running.get()) dispatcher.dispatchOnce(50, Duration.ofSeconds(30));
    }

    @Scheduled(fixedDelayString = "${campus.market.search.dispatcher.cleanup-delay-ms:5000}")
    public void cleanup() {
        if (running.get()) {
            reconciler.runOnce();
            cleanupWorker.runOnce();
        }
    }

    /** 允许运维或生命周期接缝在不使用 JVM 锁的情况下暂停/恢复调度。 */
    public void stop() { running.set(false); }
    public void start() { running.set(true); }
}
