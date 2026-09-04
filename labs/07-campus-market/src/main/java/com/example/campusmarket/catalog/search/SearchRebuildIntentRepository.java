package com.example.campusmarket.catalog.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** search_rebuild_intent 的短事务和协调锁连接内持久化边界。 */
@Repository
public class SearchRebuildIntentRepository {
    private final JdbcTemplate jdbc;

    public SearchRebuildIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public void record(String target, String previous) {
        validateTarget(target);
        jdbc.update("INSERT INTO search_rebuild_intent(id,target_index,previous_index,phase,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE previous_index=VALUES(previous_index),updated_at=CURRENT_TIMESTAMP(6)", UUID.randomUUID().toString(), target, previous, "CREATED");
    }

    void record(Connection connection, String target, String previous) {
        validateTarget(target);
        update(connection, "INSERT INTO search_rebuild_intent(id,target_index,previous_index,phase,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE previous_index=VALUES(previous_index),updated_at=CURRENT_TIMESTAMP(6)", UUID.randomUUID().toString(), target, previous, "CREATED");
    }

    public void markBuilding(String target) {
        jdbc.update("UPDATE search_rebuild_intent SET phase='BUILDING',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", target);
    }

    public void markSwitched(String target) {
        jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHED',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=?", target);
    }

    public boolean claimSwitch(String target, String owner, String token, long generation) {
        if (target == null || target.isBlank() || owner == null || owner.isBlank()
            || token == null || token.isBlank() || generation <= 0) throw new IllegalArgumentException("重建切换领取参数无效");
        return jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),generation=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='BUILDING'", owner, token, generation, target) == 1;
    }

    public boolean markSwitched(String target, String owner, String token) {
        return jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHED',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='SWITCHING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)", target, owner, token) == 1;
    }

    public void updatePrevious(String target, String previous) {
        jdbc.update("UPDATE search_rebuild_intent SET previous_index=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", previous, target);
    }

    void updatePrevious(Connection connection, String target, String previous) {
        update(connection, "UPDATE search_rebuild_intent SET previous_index=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", previous, target);
    }

    void assertSwitching(Connection connection, String target, String owner, String token, long generation) {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM search_rebuild_intent
            WHERE target_index=? AND phase='SWITCHING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """)) {
            statement.setString(1, target);
            statement.setString(2, owner);
            statement.setString(3, token);
            statement.setLong(4, generation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SearchGateRepository.SearchGateClosedException("重建切换意图已失效");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("校验重建切换意图失败", failure);
        }
    }

    boolean hasLiveSwitching(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM search_rebuild_intent
            WHERE phase='SWITCHING' AND lease_until > CURRENT_TIMESTAMP(6)
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }

    Intent latestSuccessful(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT i.target_index,i.previous_index,i.phase,i.generation,i.owner_id,i.claim_token
            FROM search_rebuild_intent i
            JOIN search_index_cleanup_task c ON c.index_name=i.target_index
            WHERE i.generation > 0 AND i.phase IN ('SWITCHED','RECONCILED') AND c.status='DONE'
            ORDER BY i.generation DESC LIMIT 1
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readIntent(result) : null;
            }
        }
    }

    Intent latestRecoverableLive(Connection connection, Set<String> live) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_index,previous_index,phase,generation,owner_id,claim_token
            FROM search_rebuild_intent
            WHERE phase <> 'RECONCILED' AND (owner_id IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY generation DESC, created_at LIMIT 20
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Intent intent = readIntent(result);
                    if (live.contains(intent.target())) return intent;
                }
                return null;
            }
        }
    }

    List<String> candidates(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_index FROM search_rebuild_intent
            WHERE phase <> 'RECONCILED' AND (owner_id IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY generation DESC, created_at LIMIT 20
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<String> candidates = new ArrayList<>();
                while (result.next()) candidates.add(result.getString(1));
                return candidates;
            }
        }
    }

    int claim(Connection connection, String target, String owner, String token) throws SQLException {
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

    Intent readClaimed(Connection connection, String target, String owner, String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_index,previous_index,phase,generation,owner_id,claim_token FROM search_rebuild_intent
            WHERE target_index=? AND owner_id=? AND claim_token=?
            """)) {
            statement.setString(1, target);
            statement.setString(2, owner);
            statement.setString(3, token);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readIntent(result) : null;
            }
        }
    }

    int markReconciled(Connection connection, String target, String owner, String token) throws SQLException {
        return update(connection, """
            UPDATE search_rebuild_intent SET phase='RECONCILED',owner_id=NULL,claim_token=NULL,
              lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6)
            WHERE target_index=? AND phase <> 'RECONCILED' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, target, owner, token);
    }

    private static Intent readIntent(ResultSet result) throws SQLException {
        return new Intent(result.getString(1), result.getString(2), result.getString(3),
            result.getLong(4), result.getString(5), result.getString(6));
    }

    private static void validateTarget(String target) {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
    }

    private static int update(Connection connection, String sql, Object... values) {
        Objects.requireNonNull(connection, "连接不能为空");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新重建意图失败", failure);
        }
    }

    record Intent(String target, String previous, String phase, long generation, String owner, String token) { }
}
