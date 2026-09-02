package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.function.Consumer;

/** 在线重建：同一 RR 快照和序列高水位，之后按序列补放并用 MySQL 门禁切换。 */
@Service
public class SearchRebuildService {
    private final JdbcTemplate jdbc;
    private final SearchProjector projector;
    private final ElasticsearchProductSearch elasticsearch;
    private final TransactionTemplate transactions;
    private final SearchGateRepository gate;
    private final String owner = "search-rebuild-" + UUID.randomUUID();
    private final Runnable beforeGateAcquire;
    private final Consumer<String> stageHook;

    @Autowired
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate) {
        this(jdbc, projector, elasticsearch, transactionManager, gate, () -> { });
    }

    /** Test seam used to make the pre-gate concurrent rebuild window deterministic. */
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate,
                                Runnable beforeGateAcquire) {
        this(jdbc, projector, elasticsearch, transactionManager, gate, beforeGateAcquire, stage -> { });
    }

    /** Full test seam for deterministic fill/replay/alias failure injection. */
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate,
                                Runnable beforeGateAcquire, Consumer<String> stageHook) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "投影器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.transactions.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
        this.beforeGateAcquire = Objects.requireNonNull(beforeGateAcquire, "切换前 barrier 不能为空");
        this.stageHook = Objects.requireNonNull(stageHook, "重建阶段 hook 不能为空");
    }

    public RebuildReport rebuild() {
        Snapshot snapshot = captureSnapshot();
        String target = elasticsearch.newRebuildIndexName();
        // Record the intent before the first ES read so a crash or connection
        // failure during target discovery is still reconciled durably.
        elasticsearch.recordRebuildIntent(target, null);
        String previous = elasticsearch.currentReadIndex();
        elasticsearch.updateRebuildIntentPrevious(target, previous);
        elasticsearch.createRebuildIndex(target);
        elasticsearch.markRebuildIntentBuilding(target);
        elasticsearch.registerRebuildTarget(target);
        boolean aliasSwitched = false;
        try {
            beforeGateAcquire.run();
            SearchGateRepository.Lease lease = gate.acquire(owner, java.time.Duration.ofSeconds(30));
            try {
                renewOrThrow(lease);
                stageHook.accept("FILL");
                for (ProductSearchPort.ProductDocument document : snapshot.documents()) {
                    renewOrThrow(lease);
                    if (!elasticsearch.renewRebuildTarget(target)) throw new IllegalStateException("目标索引租约已过期");
                    projector.projectDocumentInto(target, document, lease);
                }
                elasticsearch.refreshIndex(target);
                stageHook.accept("REPLAY");
                List<OutboxChange> changes = transactions.execute(status -> jdbc.query(
                    "SELECT listing_id, aggregate_version FROM search_outbox WHERE sequence_no > ? ORDER BY sequence_no",
                    (rs, rowNum) -> new OutboxChange(UUID.fromString(rs.getString(1)), rs.getLong(2)), snapshot.highWater()));
                for (OutboxChange change : changes) {
                    renewOrThrow(lease);
                    if (!elasticsearch.renewRebuildTarget(target)) throw new IllegalStateException("目标索引租约已过期");
                    projector.projectInto(target, change.listingId(), change.aggregateVersion(), lease);
                }
                elasticsearch.refreshIndex(target);
                // Claim the durable intent in a short transaction, then do
                // the potentially slow ES request without any DB lock.
                String switchToken = UUID.randomUUID().toString();
                gate.assertLease(lease);
                if (!elasticsearch.claimRebuildIntentSwitch(target, owner, switchToken, lease.generation())) {
                    throw new IllegalStateException("重建切换意图已被其他 worker 领取");
                }
                if (!elasticsearch.renewRebuildTarget(target)) throw new IllegalStateException("目标索引租约已过期");
                stageHook.accept("ALIAS_SWAP");
                ElasticsearchProductSearch.AliasTransition transition = elasticsearch.switchAliasesToSingleLive(target);
                // A late owner may have completed an ES request after its
                // lease was taken over. Its DB CAS is rejected; subsequent
                // reconciliation uses live aliases and the newest generation.
                gate.assertLease(lease);
                elasticsearch.cancelCleanup(target);
                Set<String> previousIndexes = transition.previousIndexes();
                for (String oldIndex : previousIndexes) {
                    if (!oldIndex.equals(target)) elasticsearch.scheduleCleanup(oldIndex);
                }
                elasticsearch.markRebuildIntentSwitched(target, owner, switchToken);
                RebuildReport report = new RebuildReport(target, snapshot.highWater(), snapshot.documents().size(), changes.size());
                aliasSwitched = true;
                return report;
            } finally {
                gate.release(lease);
            }
        } catch (RuntimeException failure) {
            // Reconciliation will compare the intent with current aliases if
            // a process dies between the external alias request and DB commit.
            if (aliasSwitched) elasticsearch.cancelCleanup(target);
            else elasticsearch.armCleanup(target);
            throw failure;
        }
    }

    private void renewOrThrow(SearchGateRepository.Lease lease) {
        if (!gate.renew(lease, java.time.Duration.ofSeconds(30))) {
            throw new IllegalStateException("重建门禁租约已过期");
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
