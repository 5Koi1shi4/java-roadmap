package com.example.campusmarket.catalog.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** 生产环境唯一领取商品源 search_outbox 的调度入口。 */
@Component
@Profile("!test")
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.product.publisher", name = "enabled",
    havingValue = "true")
public final class ProductSnapshotPublisherScheduler {
    private final ProductSnapshotPublisherDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ProductSnapshotPublisherScheduler(ProductSnapshotPublisherDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${campus.market.product.publisher.fixed-delay-ms:1000}")
    public void dispatch() {
        if (running.get()) {
            dispatcher.dispatchOnce(50, Duration.ofSeconds(30));
        }
    }

    public void stop() {
        running.set(false);
    }

    public void start() {
        running.set(true);
    }
}
