package com.example.campusmarket.product.search;

import com.example.campusmarket.product.infrastructure.ProductIndexCleanupRepository;
import com.example.campusmarket.product.infrastructure.ProductRebuildGateRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 有界清理重建遗留索引；删除前再次读取 live alias，避免误删线上索引。 */
@Component
public class ProductIndexCleanupWorker {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ProductIndexCleanupRepository cleanup;
    private final ProductRebuildGateRepository gate;
    private final ElasticsearchProductSearch search;

    public ProductIndexCleanupWorker(ProductIndexCleanupRepository cleanup,
                                     ProductRebuildGateRepository gate,
                                     ElasticsearchProductSearch search) {
        this.cleanup = Objects.requireNonNull(cleanup, "清理仓储不能为空");
        this.gate = Objects.requireNonNull(gate, "重建门禁不能为空");
        this.search = Objects.requireNonNull(search, "搜索适配器不能为空");
    }

    /** 门禁关闭时暂停领取；每次删除前均重新确认别名成员。 */
    public int cleanupOnce(String ownerId, int limit, Duration lease) {
        if (!gate.isOpenForIndexing()) {
            return 0;
        }
        List<ProductIndexCleanupRepository.Claim> claims = cleanup.claimBatch(ownerId, limit, lease);
        int completed = 0;
        for (ProductIndexCleanupRepository.Claim claim : claims) {
            try {
                if (!gate.isOpenForGeneration(claim.generation())) {
                    cleanup.markRetry(claim, Duration.ZERO, "重建门禁关闭");
                    continue;
                }
                if (liveAliasesContain(claim.indexName())) {
                    if (cleanup.markDone(claim)) {
                        completed++;
                    }
                    continue;
                }
                // 别名可能在第一次读取后发生切换，删除前必须再读一次。
                if (!gate.isOpenForGeneration(claim.generation())
                        || liveAliasesContain(claim.indexName())) {
                    cleanup.markRetry(claim, Duration.ZERO, "删除前索引重新成为 live alias");
                    continue;
                }
                search.deleteIndex(claim.indexName());
                if (cleanup.markDone(claim)) {
                    completed++;
                }
            } catch (RuntimeException failure) {
                if (claim.attemptCount() >= 3) {
                    cleanup.markFailed(claim, message(failure));
                } else {
                    cleanup.markRetry(claim, RETRY_DELAY, message(failure));
                }
            }
        }
        return completed;
    }

    private boolean liveAliasesContain(String indexName) {
        return search.readAllAliasMembers().contains(indexName);
    }

    private static String message(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "索引清理失败" : message;
    }
}
