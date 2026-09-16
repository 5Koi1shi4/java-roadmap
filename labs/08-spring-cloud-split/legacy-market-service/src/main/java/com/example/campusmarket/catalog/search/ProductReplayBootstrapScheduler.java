package com.example.campusmarket.catalog.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每次源服务启动时以保留快照完成一次高水位 bootstrap，空源也发送屏障。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(prefix = "campus.market.product.bootstrap", name = "enabled",
        havingValue = "true")
public class ProductReplayBootstrapScheduler {
    private final ProductReplayService replay;
    private final ProductEventPublisher publisher;
    private final AtomicBoolean finished = new AtomicBoolean();

    public ProductReplayBootstrapScheduler(ProductReplayService replay,
                                           ProductEventPublisher publisher) {
        this.replay = Objects.requireNonNull(replay, "replay 服务不能为空");
        this.publisher = Objects.requireNonNull(publisher, "商品发布器不能为空");
    }

    @Scheduled(fixedDelayString = "${campus.market.product.bootstrap.fixed-delay-ms:5000}",
            initialDelayString = "${campus.market.product.bootstrap.initial-delay-ms:3000}")
    public void bootstrap() {
        if (finished.get() || !publisher.brokerHealthy()) {
            return;
        }
        try {
            ProductReplayService.ReplayBatchResult result = replay.replayBatch(1000);
            if (result.complete()) {
                finished.set(true);
            }
        } catch (RuntimeException ignored) {
            // 源事件和 replay claim 持久保留；下一轮仍按同一高水位续传。
        }
    }
}
