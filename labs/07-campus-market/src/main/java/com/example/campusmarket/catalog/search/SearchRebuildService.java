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
    private final SearchAliasCoordinator coordinator;
    private final String owner = "search-rebuild-" + UUID.randomUUID();
    private final Runnable beforeGateAcquire;
    private final Consumer<String> stageHook;

    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate) {
        this(jdbc, projector, elasticsearch, transactionManager, gate, new SearchAliasCoordinator(jdbc.getDataSource()), () -> { }, stage -> { });
    }

    @Autowired
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate,
                                SearchAliasCoordinator coordinator) {
        this(jdbc, projector, elasticsearch, transactionManager, gate, coordinator, () -> { }, stage -> { });
    }

    /** 使门禁前并发重建窗口具有确定性的测试接缝。 */
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate,
                                Runnable beforeGateAcquire) {
        this(jdbc, projector, elasticsearch, transactionManager, gate, new SearchAliasCoordinator(jdbc.getDataSource()), beforeGateAcquire, stage -> { });
    }

    /** 用于确定性注入填充、补放和别名故障的完整测试接缝。 */
    public SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                                PlatformTransactionManager transactionManager, SearchGateRepository gate,
                                Runnable beforeGateAcquire, Consumer<String> stageHook) {
        this(jdbc, projector, elasticsearch, transactionManager, gate, new SearchAliasCoordinator(jdbc.getDataSource()), beforeGateAcquire, stageHook);
    }

    SearchRebuildService(JdbcTemplate jdbc, SearchProjector projector, ElasticsearchProductSearch elasticsearch,
                         PlatformTransactionManager transactionManager, SearchGateRepository gate,
                         SearchAliasCoordinator coordinator, Runnable beforeGateAcquire, Consumer<String> stageHook) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "投影器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.transactions.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
        this.coordinator = Objects.requireNonNull(coordinator, "别名协调器不能为空");
        this.beforeGateAcquire = Objects.requireNonNull(beforeGateAcquire, "切换前 barrier 不能为空");
        this.stageHook = Objects.requireNonNull(stageHook, "重建阶段 hook 不能为空");
    }

    public RebuildReport rebuild() {
        Snapshot snapshot = captureSnapshot();
        String target = elasticsearch.newRebuildIndexName();
        boolean aliasSwitched = false;
        try {
            beforeGateAcquire.run();
            SearchGateRepository.Lease lease = gate.acquire(owner, java.time.Duration.ofSeconds(30));
            try {
                coordinator.execute(java.time.Duration.ofSeconds(30), "rebuild-initialize", owner, connection -> {
                    gate.assertLease(connection, lease);
                    // 只有本 worker 持有门禁后才创建并填充意图，避免协调在门禁前窗口领取无 owner 的 CREATED 行。
                    elasticsearch.recordRebuildIntent(connection, target, null);
                    elasticsearch.ensureInitializedForAliasRead(connection);
                    String current = elasticsearch.readCurrentReadIndex();
                    elasticsearch.updateRebuildIntentPrevious(connection, target, current);
                    return null;
                });
                elasticsearch.createRebuildIndex(target);
                elasticsearch.markRebuildIntentBuilding(target);
                elasticsearch.registerRebuildTarget(target);
                renewOrThrow(lease);
                stageHook.accept("FILL");
                for (ProductSearchPort.ProductDocument document : snapshot.documents()) {
                    renewOrThrow(lease);
                    if (!elasticsearch.renewRebuildTarget(target)) throw new IllegalStateException("目标索引租约已过期");
                    projector.projectDocumentInto(target, document, lease);
                }
                stageHook.accept("REFRESH_FILL");
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
                stageHook.accept("REFRESH_REPLAY");
                elasticsearch.refreshIndex(target);
                // 先在短事务中领取持久意图，再在不持有数据库锁的情况下执行可能较慢的 ES 请求。
                String switchToken = UUID.randomUUID().toString();
                gate.assertLease(lease);
                if (!elasticsearch.claimRebuildIntentSwitch(target, owner, switchToken, lease.generation())) {
                    throw new IllegalStateException("重建切换意图已被其他 worker 领取");
                }
                if (!elasticsearch.renewRebuildTarget(target)) throw new IllegalStateException("目标索引租约已过期");
                stageHook.accept("ALIAS_SWAP");
                coordinator.execute(java.time.Duration.ofSeconds(30), "rebuild-cutover", owner, connection -> {
                    gate.assertLease(connection, lease);
                    elasticsearch.assertSwitchingIntent(connection, target, owner, switchToken, lease.generation());
                    Set<String> live = elasticsearch.readAllAliasMembers();
                    elasticsearch.stageCleanup(connection, live, target, owner, switchToken);
                    ElasticsearchProductSearch.AliasTransition result = elasticsearch.replaceAliasesWithSingleTarget(target, live);
                    stageHook.accept("AFTER_ALIAS_SWAP");
                    elasticsearch.armStagedCleanup(connection, result.previousIndexes(), Set.of(target), owner, switchToken);
                    elasticsearch.cancelCleanup(connection, target);
                    return result;
                });
                elasticsearch.markRebuildIntentSwitched(target, owner, switchToken);
                RebuildReport report = new RebuildReport(target, snapshot.highWater(), snapshot.documents().size(), changes.size());
                aliasSwitched = true;
                return report;
            } finally {
                gate.release(lease);
            }
        } catch (RuntimeException failure) {
            // 若进程在 ES 别名请求与数据库提交之间退出，协调器会将意图与当前别名重新比较。
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

    private record Snapshot(long highWater, List<ProductSearchPort.ProductDocument> documents) { }
    private record OutboxChange(UUID listingId, long aggregateVersion) { }
    public record RebuildReport(String index, long highWater, int snapshotCount, int replayCount) {
        public RebuildReport {
            Objects.requireNonNull(index); if (highWater < 0 || snapshotCount < 0 || replayCount < 0) throw new IllegalArgumentException("重建报告无效");
        }
    }
}
