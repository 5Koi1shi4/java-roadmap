package com.example.campusmarket.product.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 受索引总开关保护的旧索引清理调度；可单独关闭清理而保留索引投递。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.product.index", name = "enabled",
        havingValue = "true")
public class ProductIndexCleanupScheduler {
    private final ProductIndexCleanupWorker worker;
    private final String ownerId;
    private final int batchSize;
    private final Duration lease;
    private final boolean enabled;

    public ProductIndexCleanupScheduler(
            ProductIndexCleanupWorker worker,
            @Value("${campus.market.product.index.cleanup.owner-id:}") String configuredOwnerId,
            @Value("${campus.market.product.index.cleanup.batch-size:50}") int batchSize,
            @Value("${campus.market.product.index.cleanup.lease:PT30S}") Duration lease,
            @Value("${campus.market.product.index.cleanup.enabled:true}") boolean enabled) {
        this.worker = Objects.requireNonNull(worker, "索引清理工作器不能为空");
        this.ownerId = owner(configuredOwnerId);
        if (batchSize <= 0 || batchSize > 500) {
            throw new IllegalArgumentException("索引清理批次无效");
        }
        this.batchSize = batchSize;
        this.lease = Objects.requireNonNull(lease, "索引清理租约不能为空");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("索引清理租约无效");
        }
        this.enabled = enabled;
    }

    /** 清理异常由下一轮租约接管；当前轮只有 worker 成功删除后才会标记 DONE。 */
    @Scheduled(fixedDelayString = "${campus.market.product.index.cleanup.fixed-delay-ms:5000}",
            initialDelayString = "${campus.market.product.index.cleanup.initial-delay-ms:5000}")
    public void cleanup() {
        if (!enabled) {
            return;
        }
        try {
            worker.cleanupOnce(ownerId, batchSize, lease);
        } catch (RuntimeException ignored) {
            // 数据库/ES 故障不能伪造清理完成，任务由租约和下一轮调度恢复。
        }
    }

    private static String owner(String configuredOwnerId) {
        if (configuredOwnerId == null || configuredOwnerId.isBlank()) {
            return "product-index-cleanup-" + UUID.randomUUID();
        }
        if (configuredOwnerId.length() > 100) {
            throw new IllegalArgumentException("索引清理 owner 无效");
        }
        return configuredOwnerId;
    }
}
