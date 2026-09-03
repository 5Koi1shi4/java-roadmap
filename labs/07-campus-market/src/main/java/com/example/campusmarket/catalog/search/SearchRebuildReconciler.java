package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Repairs durable rebuild state and aliases under the same coordination lock as cutover. */
@Component
public final class SearchRebuildReconciler {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);

    private final SearchGateRepository gate;
    private final SearchAliasCoordinator coordinator;
    private final ElasticsearchProductSearch elasticsearch;
    private final String owner = "search-reconcile-" + UUID.randomUUID();

    @Autowired
    public SearchRebuildReconciler(SearchGateRepository gate,
                                   SearchAliasCoordinator coordinator, ElasticsearchProductSearch elasticsearch) {
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
        this.coordinator = Objects.requireNonNull(coordinator, "别名协调器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
    }

    public ReconcileResult runOnce() {
        return runOnce(() -> { });
    }

    /** Test seam invoked after gate state is read while the coordinator is held. */
    ReconcileResult runOnce(Runnable afterGateRead) {
        Objects.requireNonNull(afterGateRead, "门禁读取 hook 不能为空");
        return coordinator.execute(COORDINATION_TIMEOUT, connection -> {
            SearchGateRepository.GateState state = gate.readState(connection);
            afterGateRead.run();
            if (!state.isOpen()) return ReconcileResult.SKIPPED_GATE;
            if (hasLiveSwitching(connection)) return ReconcileResult.SKIPPED_SWITCHING;
            elasticsearch.ensureInitializedForAliasRead();

            RebuildIntent authoritative = latestSuccessful(connection);
            ReconcileResult result = ReconcileResult.UNCHANGED;
            if (authoritative != null) {
                Set<String> live = elasticsearch.readAllAliasMembers();
                if (!live.equals(Set.of(authoritative.target()))) {
                    ElasticsearchProductSearch.AliasTransition transition =
                        elasticsearch.replaceAliasesWithSingleTarget(authoritative.target(), live);
                    cancelCleanup(connection, authoritative.target());
                    for (String oldIndex : transition.previousIndexes()) {
                        if (!oldIndex.equals(authoritative.target())) scheduleCleanup(connection, oldIndex);
                    }
                    result = ReconcileResult.REPAIRED;
                }
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
            SELECT i.target_index,i.previous_index,i.phase,i.generation
            FROM search_rebuild_intent i
            JOIN search_index_cleanup_task c ON c.index_name=i.target_index
            WHERE i.generation > 0 AND i.phase IN ('SWITCHED','RECONCILED') AND c.status='DONE'
            ORDER BY i.generation DESC LIMIT 1
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new RebuildIntent(result.getString(1), result.getString(2),
                    result.getString(3), result.getLong(4)) : null;
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

            // Every safety decision uses the complete union of read/write members.
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
            SELECT target_index,previous_index,phase,generation FROM search_rebuild_intent
            WHERE target_index=? AND owner_id=? AND claim_token=?
            """)) {
            statement.setString(1, target);
            statement.setString(2, owner);
            statement.setString(3, token);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new RebuildIntent(result.getString(1), result.getString(2),
                    result.getString(3), result.getLong(4)) : null;
            }
        }
    }

    private String taskStatus(Connection connection, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM search_index_cleanup_task WHERE index_name=?")) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; }
        }
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
        update(connection, "UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,last_error=NULL,failure_class=NULL WHERE index_name=?", index);
    }

    private void scheduleCleanup(Connection connection, String index) throws SQLException {
        if (index == null || index.isBlank() || index.startsWith("campus-listing-000001")) return;
        update(connection, """
            INSERT INTO search_index_cleanup_task(id,index_name,status,attempt_count,available_at,created_at)
            VALUES (?,?, 'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE status=IF(status='DONE',status,'NEW'),
              available_at=IF(status='DONE',available_at,CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), index);
    }

    private void armCleanup(Connection connection, String index) throws SQLException {
        update(connection, "UPDATE search_index_cleanup_task SET status='NEW',available_at=CURRENT_TIMESTAMP(6),owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE index_name=? AND status='BUILDING'", index);
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private record RebuildIntent(String target, String previous, String phase, long generation) { }

    public enum ReconcileResult { SKIPPED_GATE, SKIPPED_SWITCHING, UNCHANGED, REPAIRED }
}
