package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在线重建：同一 RR 快照和序列高水位，之后按序列补放并用 MySQL 门禁切换。 */
@Service
public class SearchRebuildService {
    private final JdbcTemplate jdbc;
    private final SearchProjector projector;
    private final ElasticsearchProductSearch elasticsearch;
    private final TransactionTemplate transactions;
    private final SearchGateRepository gate;
    private final String owner = "search-rebuild-" + UUID.randomUUID();

    @Autowired
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "投影器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.transactions.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
    }

    public RebuildReport rebuild() {
        Snapshot snapshot = captureSnapshot();
        String target = elasticsearch.createRebuildIndex();
        // Register immediately so a process crash at any later stage leaves a
        // durable, lease-protected cleanup record instead of an orphan index.
        elasticsearch.registerRebuildTarget(target);
        AtomicBoolean aliasSwitched = new AtomicBoolean(false);
        try {
            SearchGateRepository.Lease lease = gate.acquire(owner, java.time.Duration.ofSeconds(30));
            try {
                return transactions.execute(status -> {
                    if (!gate.renew(lease, java.time.Duration.ofSeconds(30))) throw new IllegalStateException("重建门禁租约已过期");
                    for (ProductSearchPort.ProductDocument document : snapshot.documents()) {
                        if (!gate.renew(lease, java.time.Duration.ofSeconds(30))) throw new IllegalStateException("重建门禁租约已过期");
                        projector.projectDocumentInto(target, document, lease);
                    }
                    elasticsearch.refreshIndex(target);
                    List<OutboxChange> changes = jdbc.query(
                        "SELECT listing_id, aggregate_version FROM search_outbox WHERE sequence_no > ? ORDER BY sequence_no",
                        (rs, rowNum) -> new OutboxChange(UUID.fromString(rs.getString(1)), rs.getLong(2)), snapshot.highWater());
                    for (OutboxChange change : changes) {
                        if (!gate.renew(lease, java.time.Duration.ofSeconds(30))) throw new IllegalStateException("重建门禁租约已过期");
                        projector.projectInto(target, change.listingId(), change.aggregateVersion(), lease);
                    }
                    elasticsearch.refreshIndex(target);
                    gate.assertLease(lease);
                    String previous = elasticsearch.currentReadIndex();
                    elasticsearch.switchAliases(target, previous);
                    aliasSwitched.set(true);
                    elasticsearch.cancelCleanup(target);
                    if (previous != null) elasticsearch.scheduleCleanup(previous);
                    return new RebuildReport(target, snapshot.highWater(), snapshot.documents().size(), changes.size());
                });
            } finally {
                gate.release(lease);
            }
        } catch (RuntimeException failure) {
            // The target was registered immediately after creation and remains
            // a retryable cleanup candidate unless it became the live alias.
            if (aliasSwitched.get()) elasticsearch.cancelCleanup(target);
            else elasticsearch.armCleanup(target);
            throw failure;
        }
    }

    private Snapshot captureSnapshot() {
        java.util.function.Supplier<Snapshot> read = () -> {
            Long highWater = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM search_outbox", Long.class);
            List<ProductSearchPort.ProductDocument> documents = jdbc.query(
                "SELECT id,title,description,category,unit_price_fen,available_quantity,status,version FROM listing ORDER BY id",
                (rs, rowNum) -> new ProductSearchPort.ProductDocument(rs.getString("id"), rs.getString("title"),
                    rs.getString("description"), rs.getString("category"), rs.getLong("unit_price_fen"),
                    rs.getInt("available_quantity"), rs.getString("status"), Math.max(1, rs.getLong("version"))));
            return new Snapshot(highWater == null ? 0 : highWater, documents);
        };
        return Objects.requireNonNull(transactions.execute(status -> read.get()));
    }

    public void cleanupPending() { elasticsearch.cleanupPending(); }
    private record Snapshot(long highWater, List<ProductSearchPort.ProductDocument> documents) { }
    private record OutboxChange(UUID listingId, long aggregateVersion) { }
    public record RebuildReport(String index, long highWater, int snapshotCount, int replayCount) {
        public RebuildReport {
            Objects.requireNonNull(index); if (highWater < 0 || snapshotCount < 0 || replayCount < 0) throw new IllegalArgumentException("重建报告无效");
        }
    }
}
