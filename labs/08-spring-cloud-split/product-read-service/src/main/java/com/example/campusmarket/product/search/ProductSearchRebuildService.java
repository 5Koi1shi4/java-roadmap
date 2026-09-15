package com.example.campusmarket.product.search;

import com.example.campusmarket.product.infrastructure.ProductIndexCleanupRepository;
import com.example.campusmarket.product.infrastructure.ProductRebuildGateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在线重建商品读索引：在 product_read_db 的可重复读快照上建索引，补放高水位后原子切换别名。
 */
@Service
public class ProductSearchRebuildService {
    private static final Duration REBUILD_LEASE = Duration.ofSeconds(60);
    private static final int MAX_CATCH_UP_ROUNDS = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshotTransaction;
    private final ElasticsearchProductSearch search;
    private final ProductRebuildGateRepository gate;
    private final ProductIndexCleanupRepository cleanup;
    private final ReentrantLock localRun = new ReentrantLock();
    private final String ownerId = "product-rebuild-" + UUID.randomUUID();

    public ProductSearchRebuildService(JdbcTemplate jdbc,
                                       PlatformTransactionManager transactionManager,
                                       ElasticsearchProductSearch search,
                                       ProductRebuildGateRepository gate,
                                       ProductIndexCleanupRepository cleanup) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.snapshotTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTransaction.setReadOnly(true);
        this.search = Objects.requireNonNull(search, "搜索适配器不能为空");
        this.gate = Objects.requireNonNull(gate, "重建门禁不能为空");
        this.cleanup = Objects.requireNonNull(cleanup, "清理仓储不能为空");
    }

    public RebuildResult rebuildOnce() {
        return rebuildOnce(100);
    }

    /** 使用固定有界批次执行一次重建；已有其他重建租约时返回 skipped 结果。 */
    public RebuildResult rebuildOnce(int batchSize) {
        if (batchSize <= 0 || batchSize > 500) {
            throw new IllegalArgumentException("重建批次无效");
        }
        if (!localRun.tryLock()) {
            return RebuildResult.skipped();
        }

        ProductRebuildGateRepository.GateClaim claim = null;
        String targetIndex = null;
        boolean aliasSwitchAttempted = false;
        try {
            search.ensureInitializedForAliasRead();
            var acquired = gate.acquire(ownerId, REBUILD_LEASE);
            if (acquired.isEmpty()) {
                return RebuildResult.skipped();
            }
            claim = acquired.get();
            targetIndex = search.createRebuildIndex();

            Snapshot snapshot = readSnapshot();
            if (!gate.recordSnapshot(claim, targetIndex, snapshot.sequenceNo())) {
                throw new IllegalStateException("重建快照 ownership 已失效");
            }
            indexSnapshot(targetIndex, snapshot.rows());
            if (!gate.renew(claim, REBUILD_LEASE)) {
                throw new IllegalStateException("重建快照租约已失效");
            }

            long cutoverSequence = replayAfterSnapshot(claim, targetIndex, snapshot.sequenceNo(), batchSize);
            search.refreshIndex(targetIndex);
            if (!gate.markCutover(claim, cutoverSequence)) {
                throw new IllegalStateException("重建补放 ownership 已失效");
            }
            if (!gate.isOwned(claim)) {
                throw new IllegalStateException("重建别名切换 ownership 已失效");
            }

            Set<String> live = search.readAllAliasMembers();
            ProductSearchRebuildService.requireTargetAbsentFromLive(targetIndex, live);
            ProductSearchRebuildService.enqueueOldIndexes(cleanup, live, targetIndex, claim.generation());
            // CUTOVER 已先落库；从此处开始 ES 请求可能已成功但客户端收不到响应，必须保留
            // gate、目标索引和清理事实，交给恢复器根据真实 alias 状态判断。
            aliasSwitchAttempted = true;
            ElasticsearchProductSearch.AliasTransition transition =
                    search.replaceAliasesWithSingleTarget(targetIndex, live);
            if (!gate.finish(claim)) {
                throw new IllegalStateException("重建完成 ownership 已失效");
            }
            return new RebuildResult(true, targetIndex, snapshot.sequenceNo(), cutoverSequence,
                    claim.generation(), transition.previousIndexes());
        } catch (RuntimeException failure) {
            if (!aliasSwitchAttempted && targetIndex != null) {
                try {
                    search.deleteIndex(targetIndex);
                } catch (RuntimeException ignored) {
                    // 清理由持久任务或下一次重建继续处理。
                }
            }
            if (!aliasSwitchAttempted && claim != null) {
                gate.release(claim);
            }
            throw failure;
        } finally {
            localRun.unlock();
        }
    }

    private Snapshot readSnapshot() {
        Snapshot snapshot = snapshotTransaction.execute(status -> {
            Long sequence = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence_no),0) FROM product_index_outbox", Long.class);
            List<Projection> rows = jdbc.query("""
                SELECT listing_id,aggregate_version,title,description,category,
                       unit_price_fen,available_quantity,status
                FROM product_projection
                ORDER BY listing_id
                """, (result, row) -> new Projection(
                    result.getString("listing_id"), result.getLong("aggregate_version"),
                    result.getString("title"), result.getString("description"),
                    result.getString("category"), result.getLong("unit_price_fen"),
                    result.getInt("available_quantity"), result.getString("status")));
            return new Snapshot(sequence == null ? 0L : sequence, rows);
        });
        if (snapshot == null) {
            throw new IllegalStateException("读侧一致性快照为空");
        }
        return snapshot;
    }

    private void indexSnapshot(String targetIndex, List<Projection> rows) {
        for (Projection projection : rows) {
            writeProjection(targetIndex, projection);
        }
    }

    private long replayAfterSnapshot(ProductRebuildGateRepository.GateClaim claim,
                                     String targetIndex, long snapshotSequence, int batchSize) {
        long cursor = snapshotSequence + 1;
        long last = snapshotSequence;
        for (int round = 0; round < MAX_CATCH_UP_ROUNDS; round++) {
            Long max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence_no),0) FROM product_index_outbox", Long.class);
            long highwater = max == null ? 0 : max;
            if (highwater < cursor) {
                return last;
            }
            List<OutboxRow> rows = jdbc.query("""
                SELECT sequence_no,listing_id,aggregate_version
                FROM product_index_outbox
                WHERE sequence_no >= ? AND sequence_no <= ?
                ORDER BY sequence_no
                LIMIT ?
                """, (result, row) -> new OutboxRow(result.getLong("sequence_no"),
                    result.getString("listing_id"), result.getLong("aggregate_version")),
                    cursor, highwater, batchSize);
            if (rows.isEmpty()) {
                cursor = highwater + 1;
                last = highwater;
                continue;
            }
            for (OutboxRow row : rows) {
                Projection projection = projection(row.listingId());
                if (projection != null && projection.aggregateVersion() >= row.aggregateVersion()) {
                    writeProjection(targetIndex, projection);
                } else {
                    long version = projection == null
                            ? row.aggregateVersion() : projection.aggregateVersion();
                    search.tombstoneInto(targetIndex, row.listingId(), version);
                }
                last = Math.max(last, row.sequenceNo());
                cursor = row.sequenceNo() + 1;
            }
            if (!gate.renew(claim, REBUILD_LEASE)) {
                throw new IllegalStateException("重建补放租约已失效");
            }
        }
        throw new IllegalStateException("重建期间变更持续增长");
    }

    private Projection projection(String listingId) {
        List<Projection> rows = jdbc.query("""
            SELECT listing_id,aggregate_version,title,description,category,
                   unit_price_fen,available_quantity,status
            FROM product_projection
            WHERE listing_id=?
            """, (result, row) -> new Projection(
                result.getString("listing_id"), result.getLong("aggregate_version"),
                result.getString("title"), result.getString("description"),
                result.getString("category"), result.getLong("unit_price_fen"),
                result.getInt("available_quantity"), result.getString("status")), listingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeProjection(String targetIndex, Projection projection) {
        if (isVisible(projection)) {
            search.indexInto(targetIndex, projection.document());
        } else {
            search.tombstoneInto(targetIndex, projection.listingId(), projection.aggregateVersion());
        }
    }

    private static boolean isVisible(Projection projection) {
        return "ON_SALE".equals(projection.status()) && projection.availableQuantity() > 0;
    }

    private static void requireTargetAbsentFromLive(String targetIndex, Set<String> live) {
        if (live.contains(targetIndex)) {
            throw new IllegalStateException("重建目标已经是 live alias");
        }
    }

    private static void enqueueOldIndexes(ProductIndexCleanupRepository cleanup,
                                           Set<String> live, String targetIndex, long generation) {
        for (String oldIndex : live) {
            if (!targetIndex.equals(oldIndex)) {
                cleanup.enqueue(oldIndex, generation);
            }
        }
    }

    private record Snapshot(long sequenceNo, List<Projection> rows) {
        private Snapshot {
            rows = List.copyOf(Objects.requireNonNull(rows, "快照商品不能为空"));
        }
    }

    private record OutboxRow(long sequenceNo, String listingId, long aggregateVersion) {
    }

    private record Projection(String listingId, long aggregateVersion, String title, String description,
                              String category, long unitPriceFen, int availableQuantity, String status) {
        private ProductSearchPort.ProductDocument document() {
            return new ProductSearchPort.ProductDocument(listingId, title, description, category,
                    unitPriceFen, availableQuantity, status, aggregateVersion);
        }
    }

    public record RebuildResult(boolean switched, String targetIndex, long snapshotSequenceNo,
                                long cutoverSequenceNo, long generation, Set<String> previousIndexes) {
        public RebuildResult {
            previousIndexes = Set.copyOf(Objects.requireNonNull(previousIndexes, "旧索引集合不能为空"));
            if (switched && (targetIndex == null || targetIndex.isBlank() || generation <= 0
                    || snapshotSequenceNo < 0 || cutoverSequenceNo < snapshotSequenceNo)) {
                throw new IllegalArgumentException("重建结果无效");
            }
        }

        private static RebuildResult skipped() {
            return new RebuildResult(false, null, 0, 0, 0, Set.of());
        }
    }
}
