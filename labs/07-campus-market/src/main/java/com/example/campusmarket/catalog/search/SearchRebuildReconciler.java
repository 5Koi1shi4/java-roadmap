package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 在与切换相同的协调锁下修复持久重建状态和别名。 */
@Component
public final class SearchRebuildReconciler {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);

    private final SearchGateRepository gate;
    private final SearchAliasCoordinator coordinator;
    private final ElasticsearchProductSearch elasticsearch;
    private final SearchIndexCleanupRepository cleanupRepository;
    private final MeterRegistry metrics;
    private final String owner = "search-reconcile-" + UUID.randomUUID();

    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch) {
        this(gate, coordinator, elasticsearch, elasticsearch.cleanupRepository(), new SimpleMeterRegistry());
    }

    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch,
                                   MeterRegistry metrics) {
        this(gate, coordinator, elasticsearch, elasticsearch.cleanupRepository(), metrics);
    }

    @Autowired
    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch,
                                   SearchIndexCleanupRepository cleanupRepository, MeterRegistry metrics) {
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
        this.coordinator = Objects.requireNonNull(coordinator, "别名协调器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "清理仓储不能为空");
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
            if (hasLiveSwitching(connection)) {
                metrics.counter("search.alias.reconciliation.skip", "reason", "active_intent").increment();
                return ReconcileResult.SKIPPED_SWITCHING;
            }
            elasticsearch.ensureInitializedForAliasRead(connection);

            Set<String> live = elasticsearch.readAllAliasMembers();
            RebuildIntent liveIntent = latestRecoverableLiveIntent(connection, live);
            RebuildIntent authoritative = latestSuccessful(connection);
            ReconcileResult result = ReconcileResult.UNCHANGED;
            // 未协调意图中的 live target 说明外部别名请求已完成，但数据库阶段更新尚未完成。
            // 它优先于较旧的持久成功 target，否则协调可能将别名回滚。
            RebuildIntent target = liveIntent != null ? liveIntent : authoritative;
            if (liveIntent != null && liveIntent.owner() != null && liveIntent.token() != null) {
                elasticsearch.recoverStagedCleanup(connection, live, liveIntent.owner(), liveIntent.token());
            }
            if (target != null && !live.equals(Set.of(target.target()))) {
                String repairToken = UUID.randomUUID().toString();
                elasticsearch.stageCleanup(connection, live, target.target(), owner, repairToken);
                ElasticsearchProductSearch.AliasTransition transition =
                    elasticsearch.replaceAliasesWithSingleTarget(target.target(), live);
                elasticsearch.armStagedCleanup(connection, transition.previousIndexes(), Set.of(target.target()), owner, repairToken);
                elasticsearch.cancelCleanup(connection, target.target());
                result = ReconcileResult.REPAIRED;
            }
            reconcileRebuildIntents(connection);
            return result;
        });
    }

    private boolean hasLiveSwitching(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM search_rebuild_intent
            WHERE phase='SWITCHING' AND lease_until > CURRENT_TIMESTAMP(6)
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }

    private RebuildIntent latestSuccessful(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT i.target_index,i.previous_index,i.phase,i.generation,i.owner_id,i.claim_token
            FROM search_rebuild_intent i
            JOIN search_index_cleanup_task c ON c.index_name=i.target_index
            WHERE i.generation > 0 AND i.phase IN ('SWITCHED','RECONCILED') AND c.status='DONE'
            ORDER BY i.generation DESC LIMIT 1
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new RebuildIntent(result.getString(1), result.getString(2),
                    result.getString(3), result.getLong(4), result.getString(5), result.getString(6)) : null;
            }
        }
    }

    private RebuildIntent latestRecoverableLiveIntent(Connection connection, Set<String> live) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_index,previous_index,phase,generation,owner_id,claim_token
            FROM search_rebuild_intent
            WHERE phase <> 'RECONCILED' AND (owner_id IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY generation DESC, created_at LIMIT 20
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    RebuildIntent intent = new RebuildIntent(result.getString(1), result.getString(2),
                        result.getString(3), result.getLong(4), result.getString(5), result.getString(6));
                    if (live.contains(intent.target())) return intent;
                }
                return null;
            }
        }
    }

    private void reconcileRebuildIntents(Connection connection) throws SQLException {
        List<String> candidates;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_index FROM search_rebuild_intent
            WHERE phase <> 'RECONCILED' AND (owner_id IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY generation DESC, created_at LIMIT 20
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                candidates = new java.util.ArrayList<>();
                while (result.next()) candidates.add(result.getString(1));
            }
        }
        for (String target : candidates) {
            String token = UUID.randomUUID().toString();
            if (claim(connection, target, token) != 1) continue;
            RebuildIntent intent = readClaimed(connection, target, token);
            if (intent == null) continue;

            // 所有安全判断都使用 read/write 成员的完整并集。
            Set<String> live = elasticsearch.readAllAliasMembers();
            if (live.contains(intent.target())) {
                cancelCleanup(connection, intent.target());
                if (intent.previous() != null && !live.contains(intent.previous())) scheduleCleanup(connection, intent.previous());
                markReconciled(connection, intent.target(), token);
            } else if (Set.of("BUILDING", "SWITCHING", "SWITCHED").contains(intent.phase())) {
                String taskStatus = taskStatus(connection, intent.target());
                if (taskStatus == null) scheduleCleanup(connection, intent.target());
                else if (!"BUILDING".equals(taskStatus)) armCleanup(connection, intent.target());
                else continue;
                markReconciled(connection, intent.target(), token);
            } else if ("CREATED".equals(intent.phase())) {
                scheduleCleanup(connection, intent.target());
                markReconciled(connection, intent.target(), token);
            }
        }
    }

    private int claim(Connection connection, String target, String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE search_rebuild_intent
            SET owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6))
            WHERE target_index=? AND phase <> 'RECONCILED'
              AND (owner_id IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
            """)) {
            statement.setString(1, owner);
            statement.setString(2, token);
            statement.setString(3, target);
            return statement.executeUpdate();
        }
    }

    private RebuildIntent readClaimed(Connection connection, String target, String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_index,previous_index,phase,generation,owner_id,claim_token FROM search_rebuild_intent
            WHERE target_index=? AND owner_id=? AND claim_token=?
            """)) {
            statement.setString(1, target);
            statement.setString(2, owner);
            statement.setString(3, token);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new RebuildIntent(result.getString(1), result.getString(2),
                    result.getString(3), result.getLong(4), result.getString(5), result.getString(6)) : null;
            }
        }
    }

    private String taskStatus(Connection connection, String index) throws SQLException {
        return cleanupRepository.status(connection, index);
    }

    private void markReconciled(Connection connection, String target, String token) throws SQLException {
        update(connection, """
            UPDATE search_rebuild_intent SET phase='RECONCILED',owner_id=NULL,claim_token=NULL,
              lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6)
            WHERE target_index=? AND phase <> 'RECONCILED' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, target, owner, token);
    }

    private void cancelCleanup(Connection connection, String index) throws SQLException {
        cleanupRepository.cancel(connection, index);
    }

    private void scheduleCleanup(Connection connection, String index) throws SQLException {
        cleanupRepository.schedule(connection, index);
    }

    private void armCleanup(Connection connection, String index) throws SQLException {
        cleanupRepository.arm(connection, index);
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private record RebuildIntent(String target, String previous, String phase, long generation, String owner, String token) { }

    public enum ReconcileResult { SKIPPED_GATE, SKIPPED_SWITCHING, UNCHANGED, REPAIRED }
}
