package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 在线重建：同一 RR 快照和序列高水位，之后按序列补放并用 MySQL 门禁切换。 */
@Service
public class SearchRebuildService {
    private final JdbcTemplate jdbc;
    private final SearchProjector projector;
    private final ElasticsearchProductSearch elasticsearch;
    private final TransactionTemplate transactions;
    private final SearchGateRepository gate;
    private final String owner = "search-rebuild-" + UUID.randomUUID();

    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch) {
        this(jdbc, projector, elasticsearch, null, null);
    }
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager) {
        this(jdbc, projector, elasticsearch, transactionManager, null);
    }
    @Autowired
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "投影器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (transactions != null) transactions.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        this.gate = gate;
    }

    public RebuildReport rebuild() {
        Snapshot snapshot = captureSnapshot();
        String target = elasticsearch.createRebuildIndex();
        int snapshotCount = 0;
        boolean aliasSwitched = false;
        try {
            for (ProductSearchPort.ProductDocument document : snapshot.documents()) {
                projector.projectDocumentInto(target, document);
                snapshotCount++;
            }
            elasticsearch.refreshIndex(target);
            SearchGateRepository.Lease lease = gate == null ? null : gate.acquire(owner);
            try {
                List<OutboxChange> changes = jdbc.query(
                    "SELECT listing_id, aggregate_version FROM search_outbox WHERE sequence_no > ? ORDER BY sequence_no",
                    (rs, rowNum) -> new OutboxChange(UUID.fromString(rs.getString(1)), rs.getLong(2)), snapshot.highWater());
                for (OutboxChange change : changes) projector.projectInto(target, change.listingId(), change.aggregateVersion());
                elasticsearch.refreshIndex(target);
                String previous = elasticsearch.currentReadIndex();
                elasticsearch.switchAliases(target, previous);
                aliasSwitched = true;
                if (previous != null) elasticsearch.scheduleCleanup(previous);
                elasticsearch.cleanupPending();
                return new RebuildReport(target, snapshot.highWater(), snapshotCount, changes.size());
            } finally {
                if (lease != null) gate.release(lease);
            }
        } catch (RuntimeException failure) {
            // Once the alias is live, it is never a cleanup candidate. Before the
            // switch, persist the target for retryable deletion after a crash.
            if (!aliasSwitched) elasticsearch.scheduleCleanup(target);
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
        return transactions == null ? read.get() : Objects.requireNonNull(transactions.execute(status -> read.get()));
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
