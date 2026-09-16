package com.example.campusmarket.product.search;

import com.example.campusmarket.product.infrastructure.ProductIndexCleanupRepository;
import com.example.campusmarket.product.infrastructure.ProductRebuildGateRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 根据持久 intent 与真实 ES 别名接管过期重建；未证实安全时保持门禁关闭。 */
@Service
public class ProductSearchRebuildRecoveryService {
    private static final Duration LEASE = Duration.ofSeconds(60);
    private final ProductRebuildGateRepository gate;
    private final ProductIndexCleanupRepository cleanup;
    private final ElasticsearchProductSearch search;
    private final String ownerId = "product-rebuild-recovery-" + UUID.randomUUID();

    public ProductSearchRebuildRecoveryService(ProductRebuildGateRepository gate,
                                               ProductIndexCleanupRepository cleanup,
                                               ElasticsearchProductSearch search) {
        this.gate = Objects.requireNonNull(gate, "重建门禁不能为空");
        this.cleanup = Objects.requireNonNull(cleanup, "索引清理仓储不能为空");
        this.search = Objects.requireNonNull(search, "搜索适配器不能为空");
    }

    public RecoveryResult recoverOnce() {
        var acquired = gate.claimExpiredRecovery(ownerId, LEASE);
        if (acquired.isEmpty()) {
            return RecoveryResult.SKIPPED;
        }
        var state = acquired.get();
        var claim = state.claim();
        String target = state.rebuildIndex();
        if (target == null) {
            if (!gate.release(claim)) {
                throw new IllegalStateException("空目标重建恢复 ownership 已失效");
            }
            return RecoveryResult.RELEASED;
        }

        Set<String> read = search.currentReadIndexes();
        Set<String> write = search.currentWriteIndexes();
        boolean readLive = read.contains(target);
        boolean writeLive = write.contains(target);
        if ("CUTOVER".equals(state.intent()) && read.equals(Set.of(target))
                && write.equals(Set.of(target))) {
            if (!gate.finish(claim)) {
                throw new IllegalStateException("切换恢复 ownership 已失效");
            }
            return RecoveryResult.FINISHED;
        }
        // 部分别名已指向目标、或 BUILDING 目标意外上线，绝不能删索引或开放门禁。
        if (readLive || writeLive) {
            return RecoveryResult.NEEDS_ATTENTION;
        }
        cleanup.enqueue(target, claim.generation());
        if (!gate.release(claim)) {
            throw new IllegalStateException("重建清理恢复 ownership 已失效");
        }
        return RecoveryResult.RELEASED;
    }

    public enum RecoveryResult {
        SKIPPED, RELEASED, FINISHED, NEEDS_ATTENTION
    }
}
