package com.example.campusmarket.catalog.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/** 在线重建：一致性高水位快照 → 高水位后的补放 → 写入门禁 → 原子别名切换。 */
@Service
public final class SearchRebuildService {
    private final JdbcTemplate jdbc;
    private final SearchProjector projector;
    private final ElasticsearchProductSearch elasticsearch;
    private final TransactionTemplate transactions;

    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector,
                                ElasticsearchProductSearch elasticsearch) {
        this(jdbc, projector, elasticsearch, null);
    }

    @Autowired
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector,
                                ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "搜索投影器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (transactions != null) transactions.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    }

    /**
     * 失败时旧 read alias 不会被移除；调用方可在连接恢复后再次执行，天然可恢复。
     */
    public RebuildReport rebuild() {
        Snapshot snapshot = captureSnapshot();
        Instant highWater = snapshot.highWater();
        String target = elasticsearch.createRebuildIndex();
        int snapshotCount = 0;
        try {
            for (UUID id : snapshot.listingIds()) {
                projector.projectInto(target, id, currentVersion(id));
                snapshotCount++;
            }
            elasticsearch.refreshIndex(target);

            Lock gate = projector.acquireRebuildWriteGate();
            gate.lock();
            try {
                // Events committed after the snapshot high-water are applied while all normal
                // projectors are stopped. MySQL remains the source of document fields.
                List<OutboxChange> changes = jdbc.query(
                    "SELECT listing_id, aggregate_version FROM search_outbox WHERE created_at > ? ORDER BY created_at,id",
                    (rs, rowNum) -> new OutboxChange(UUID.fromString(rs.getString(1)), rs.getLong(2)),
                    Timestamp.from(highWater));
                for (OutboxChange change : changes) {
                    projector.projectInto(target, change.listingId(), change.aggregateVersion());
                }
                elasticsearch.refreshIndex(target);
                String previous = elasticsearch.currentReadIndex();
                elasticsearch.switchAliases(target, previous);
                return new RebuildReport(target, highWater, snapshotCount, changes.size());
            } finally {
                gate.unlock();
            }
        } catch (RuntimeException failure) {
            // Keep the old aliases untouched. The orphan target can safely be ignored by the next run.
            throw failure;
        }
    }

    private Snapshot captureSnapshot() {
        java.util.function.Supplier<Snapshot> read = () -> {
            Instant highWater = jdbc.queryForObject(
                "SELECT COALESCE(MAX(created_at), CURRENT_TIMESTAMP(6)) FROM search_outbox",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
            List<UUID> ids = jdbc.query(
                "SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity > 0 ORDER BY id",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)));
            return new Snapshot(highWater, ids);
        };
        return transactions == null ? read.get() : transactions.execute(status -> read.get());
    }

    private long currentVersion(UUID id) {
        Long version = jdbc.queryForObject("SELECT version FROM listing WHERE id=?", Long.class, id.toString());
        return version == null || version <= 0 ? 1 : version;
    }

    private record OutboxChange(UUID listingId, long aggregateVersion) { }

    private record Snapshot(Instant highWater, List<UUID> listingIds) { }

    public record RebuildReport(String index, Instant highWater, int snapshotCount, int replayCount) {
        public RebuildReport {
            Objects.requireNonNull(index, "索引不能为空");
            Objects.requireNonNull(highWater, "高水位不能为空");
            if (snapshotCount < 0 || replayCount < 0) throw new IllegalArgumentException("重建计数不能为负数");
        }
    }
}
