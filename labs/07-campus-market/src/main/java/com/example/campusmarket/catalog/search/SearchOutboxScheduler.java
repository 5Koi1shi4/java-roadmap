package com.example.campusmarket.catalog.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Production entry point for the outbox worker; disabled unless explicitly enabled. */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.search.dispatcher", name = "enabled", havingValue = "true")
public final class SearchOutboxScheduler {
    private final SearchOutboxDispatcher dispatcher;
    private final ElasticsearchProductSearch search;
    private final SearchRebuildReconciler reconciler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SearchOutboxScheduler(SearchOutboxDispatcher dispatcher, ElasticsearchProductSearch search,
                                 SearchRebuildReconciler reconciler) {
        this.dispatcher = dispatcher;
        this.search = search;
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
            search.cleanupPending();
        }
    }

    /** Allows an operator or lifecycle hook to pause/resume dispatch without a JVM lock. */
    public void stop() { running.set(false); }
    public void start() { running.set(true); }
}
