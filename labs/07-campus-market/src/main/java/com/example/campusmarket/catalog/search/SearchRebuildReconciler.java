package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 在与切换相同的协调锁下修复持久重建状态和别名。 */
@Component
@Profile("!test")
public final class SearchRebuildReconciler {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);

    private final SearchGateRepository gate;
    private final SearchAliasCoordinator coordinator;
    private final ElasticsearchProductSearch elasticsearch;
    private final SearchIndexCleanupRepository cleanupRepository;
    private final SearchRebuildIntentRepository intentRepository;
    private final MeterRegistry metrics;
    private final String owner = "search-reconcile-" + UUID.randomUUID();

    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch) {
        this(gate, coordinator, elasticsearch, new SearchIndexCleanupRepository(new JdbcTemplate(coordinator.dataSource())),
            new SearchRebuildIntentRepository(new JdbcTemplate(coordinator.dataSource())), new SimpleMeterRegistry());
    }

    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch,
                                   MeterRegistry metrics) {
        this(gate, coordinator, elasticsearch, new SearchIndexCleanupRepository(new JdbcTemplate(coordinator.dataSource())),
            new SearchRebuildIntentRepository(new JdbcTemplate(coordinator.dataSource())), metrics);
    }

    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch,
                                   SearchIndexCleanupRepository cleanupRepository, MeterRegistry metrics) {
        this(gate, coordinator, elasticsearch, cleanupRepository,
            new SearchRebuildIntentRepository(new JdbcTemplate(coordinator.dataSource())), metrics);
    }

    @Autowired
    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch,
                                   SearchIndexCleanupRepository cleanupRepository,
                                   SearchRebuildIntentRepository intentRepository, MeterRegistry metrics) {
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
        this.coordinator = Objects.requireNonNull(coordinator, "别名协调器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "清理仓储不能为空");
        this.intentRepository = Objects.requireNonNull(intentRepository, "重建意图仓储不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标注册表不能为空");
    }

    public ReconcileResult runOnce() {
        return runOnce(() -> { });
    }

    /** 协调器持锁读取门禁状态后调用的测试接缝。 */
    ReconcileResult runOnce(Runnable afterGateRead) {
        Objects.requireNonNull(afterGateRead, "门禁读取 hook 不能为空");
        return coordinator.execute(COORDINATION_TIMEOUT, "reconcile", owner, connection -> {
            SearchGateRepository.GateState state = gate.readState(connection);
            afterGateRead.run();
            if (!state.isOpen()) {
                metrics.counter("search.alias.reconciliation.skip", "reason", "gate").increment();
                return ReconcileResult.SKIPPED_GATE;
            }
            if (intentRepository.hasLiveSwitching(connection)) {
                metrics.counter("search.alias.reconciliation.skip", "reason", "active_intent").increment();
                return ReconcileResult.SKIPPED_SWITCHING;
            }
            elasticsearch.ensureInitializedForAliasRead(connection);

            Set<String> live = elasticsearch.readAllAliasMembers();
            SearchRebuildIntentRepository.Intent liveIntent = intentRepository.latestRecoverableLive(connection, live);
            SearchRebuildIntentRepository.Intent authoritative = intentRepository.latestSuccessful(connection);
            ReconcileResult result = ReconcileResult.UNCHANGED;
            // 未协调意图中的 live target 说明外部别名请求已完成，但数据库阶段更新尚未完成。
            // 它优先于较旧的持久成功 target，否则协调可能将别名回滚。
            SearchRebuildIntentRepository.Intent target = liveIntent != null ? liveIntent : authoritative;
            if (liveIntent != null && liveIntent.owner() != null && liveIntent.token() != null) {
                cleanupRepository.recover(connection, live, liveIntent.owner(), liveIntent.token());
            }
            if (target != null && !live.equals(Set.of(target.target()))) {
                String repairToken = UUID.randomUUID().toString();
                cleanupRepository.stage(connection, live, target.target(), owner, repairToken);
                ElasticsearchProductSearch.AliasTransition transition =
                    elasticsearch.replaceAliasesWithSingleTarget(target.target(), live);
                cleanupRepository.arm(connection, transition.previousIndexes(), Set.of(target.target()), owner, repairToken);
                cleanupRepository.cancel(connection, target.target());
                result = ReconcileResult.REPAIRED;
            }
            reconcileRebuildIntents(connection);
            return result;
        });
    }

    private void reconcileRebuildIntents(Connection connection) throws SQLException {
        List<String> candidates = intentRepository.candidates(connection);
        for (String target : candidates) {
            String token = UUID.randomUUID().toString();
            if (intentRepository.claim(connection, target, owner, token) != 1) continue;
            SearchRebuildIntentRepository.Intent intent = intentRepository.readClaimed(connection, target, owner, token);
            if (intent == null) continue;

            // 所有安全判断都使用 read/write 成员的完整并集。
            Set<String> live = elasticsearch.readAllAliasMembers();
            if (live.contains(intent.target())) {
                cleanupRepository.cancel(connection, intent.target());
                if (intent.previous() != null && !live.contains(intent.previous())) cleanupRepository.schedule(connection, intent.previous());
                intentRepository.markReconciled(connection, intent.target(), owner, token);
            } else if (Set.of("BUILDING", "SWITCHING", "SWITCHED").contains(intent.phase())) {
                String taskStatus = taskStatus(connection, intent.target());
                if (taskStatus == null) cleanupRepository.schedule(connection, intent.target());
                else if (!"BUILDING".equals(taskStatus)) cleanupRepository.arm(connection, intent.target());
                else continue;
                intentRepository.markReconciled(connection, intent.target(), owner, token);
            } else if ("CREATED".equals(intent.phase())) {
                cleanupRepository.schedule(connection, intent.target());
                intentRepository.markReconciled(connection, intent.target(), owner, token);
            }
        }
    }

    private String taskStatus(Connection connection, String index) throws SQLException {
        return cleanupRepository.status(connection, index);
    }

    private void armCleanup(Connection connection, String index) throws SQLException {
        cleanupRepository.arm(connection, index);
    }

    public enum ReconcileResult { SKIPPED_GATE, SKIPPED_SWITCHING, UNCHANGED, REPAIRED }
}
