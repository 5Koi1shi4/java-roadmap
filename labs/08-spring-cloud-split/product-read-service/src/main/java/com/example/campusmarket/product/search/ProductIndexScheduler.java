package com.example.campusmarket.product.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 受配置门控的读侧索引待办调度；外部依赖失败时按固定短间隔重试。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.product.index", name = "enabled",
        havingValue = "true")
public class ProductIndexScheduler {
    private final ProductIndexDispatcher dispatcher;
    private final String ownerId = "product-index-" + UUID.randomUUID();
    private final int batchSize;
    private final Duration lease;

    public ProductIndexScheduler(ProductIndexDispatcher dispatcher,
                                 @Value("${campus.market.product.index.batch-size:50}") int batchSize,
                                 @Value("${campus.market.product.index.lease:PT30S}") Duration lease) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "索引待办投递器不能为空");
        if (batchSize <= 0 || batchSize > 500) {
            throw new IllegalArgumentException("索引待办批次无效");
        }
        this.batchSize = batchSize;
        this.lease = Objects.requireNonNull(lease, "索引待办租约不能为空");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("索引待办租约无效");
        }
    }

    /** fixedDelay 同时提供正常轮询和外部依赖故障后的有界退避。 */
    @Scheduled(fixedDelayString = "${campus.market.product.index.fixed-delay-ms:1000}",
            initialDelayString = "${campus.market.product.index.initial-delay-ms:1000}")
    public void dispatch() {
        try {
            dispatcher.dispatchOnce(ownerId, batchSize, lease);
        } catch (RuntimeException ignored) {
            // 待办仍由 lease/token fencing 保留，下一轮按固定延迟接管或重试。
        }
    }
}
