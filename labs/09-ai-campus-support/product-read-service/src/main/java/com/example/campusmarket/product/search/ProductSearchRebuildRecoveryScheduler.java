package com.example.campusmarket.product.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** 重启或超时后轮询过期的重建记录。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "campus.market.product.rebuild-recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ProductSearchRebuildRecoveryScheduler {
    private final ProductSearchRebuildRecoveryService recovery;

    public ProductSearchRebuildRecoveryScheduler(ProductSearchRebuildRecoveryService recovery) {
        this.recovery = Objects.requireNonNull(recovery, "重建恢复服务不能为空");
    }

    @Scheduled(fixedDelayString = "${campus.market.product.rebuild-recovery.fixed-delay-ms:5000}",
            initialDelayString = "${campus.market.product.rebuild-recovery.initial-delay-ms:5000}")
    public void recover() {
        try {
            recovery.recoverOnce();
        } catch (RuntimeException ignored) {
            // 数据库或 ES 暂不可用时保留重建门禁，下一轮继续核对。
        }
    }
}
