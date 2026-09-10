package com.example.campusmarket.catalog.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 短事务领取和 owner/token fencing 的清理状态变更。 */
@Repository
@Profile("!test")
public class SearchIndexCleanupRepository {
    private static final String COMPLETE_SQL = """
        UPDATE search_index_cleanup_task
        SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,
            last_error=NULL,failure_class=NULL
        WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
          AND lease_until > CURRENT_TIMESTAMP(6)
        """;
    private final JdbcTemplate jdbc;
    private final String owner = "search-cleanup-" + UUID.randomUUID();

    public SearchIndexCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CleanupClaim> claimBatch(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("清理领取上限必须为正数");
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class);
        if (!"OPEN".equals(mode)) return List.of();

        jdbc.update("""
            UPDATE search_index_cleanup_task
            SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                last_error='cleanup attempt limit exhausted',failure_class='TRANSIENT'
            WHERE status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count >= 3
            """);

        List<CleanupClaim> claims = new ArrayList<>();
        List<ClaimSeed> candidates = jdbc.query("""
            SELECT id,index_name,attempt_count
            FROM search_index_cleanup_task
            WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
               OR (status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count < 3)
               OR (status='BUILDING' AND lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY created_at
            LIMIT ? FOR UPDATE SKIP LOCKED
            """, (rs, rowNum) -> new ClaimSeed(rs.getString("id"), rs.getString("index_name"), rs.getInt("attempt_count")), limit);
        for (ClaimSeed candidate : candidates) {
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                UPDATE search_index_cleanup_task
                SET status='RUNNING',owner_id=?,claim_token=?,
                    lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),attempt_count=attempt_count+1
                WHERE id=? AND (
                    (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                    OR (status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count < 3)
                    OR (status='BUILDING' AND lease_until <= CURRENT_TIMESTAMP(6))
                )
                """, owner, token, candidate.id());
            if (changed == 1) {
                Timestamp leaseUntil = jdbc.queryForObject(
                    "SELECT lease_until FROM search_index_cleanup_task WHERE id=? AND owner_id=? AND claim_token=?",
                    Timestamp.class, candidate.id(), owner, token);
                claims.add(new CleanupClaim(candidate.id(), candidate.indexName(), candidate.attemptCount() + 1,
                    owner, token, leaseUntil));
            }
        }
        return claims;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int complete(CleanupClaim claim) {
        Objects.requireNonNull(claim, "清理领取不能为空");
        return jdbc.update(COMPLETE_SQL, claim.id(), claim.owner(), claim.token());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int fail(CleanupClaim claim, Throwable failure) {
        Objects.requireNonNull(claim, "清理领取不能为空");
        Objects.requireNonNull(failure, "清理失败不能为空");
        String kind = permanentFailure(failure) ? "PERMANENT" : "TRANSIENT";
        String message = failure.getMessage() == null ? kind : failure.getMessage().substring(0, Math.min(500, failure.getMessage().length()));
        String status = claim.attemptCount() >= 3 ? "FAILED" : "NEW";
        return jdbc.update("""
            UPDATE search_index_cleanup_task
            SET status=?,owner_id=NULL,claim_token=NULL,lease_until=NULL,
                available_at=TIMESTAMPADD(SECOND,10,CURRENT_TIMESTAMP(6)),last_error=?,failure_class=?
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, status, message, kind, claim.id(), claim.owner(), claim.token());
    }

    /** 协调锁等待超时时退回待处理，且不消耗清理业务尝试次数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retryAfterCoordinationFailure(CleanupClaim claim,
                                              SearchAliasCoordinator.SearchCoordinationTimeoutException failure) {
        Objects.requireNonNull(claim, "清理领取不能为空");
        Objects.requireNonNull(failure, "协调锁超时不能为空");
        String message = failure.getMessage() == null ? "alias coordination timeout" :
            failure.getMessage().substring(0, Math.min(500, failure.getMessage().length()));
        return jdbc.update("""
            UPDATE search_index_cleanup_task
            SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                attempt_count=GREATEST(attempt_count-1,0),
                available_at=TIMESTAMPADD(SECOND,10,CURRENT_TIMESTAMP(6)),last_error=?,failure_class='TRANSIENT'
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
            """, message, claim.id(), claim.owner(), claim.token());
    }

    /** 在协调器持锁临界区中使用的 owner/token 检查。 */
    boolean owned(Connection connection, CleanupClaim claim) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(claim, "清理领取不能为空");
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM search_index_cleanup_task
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)) {
            statement.setString(1, claim.id());
            statement.setString(2, claim.owner());
            statement.setString(3, claim.token());
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        } catch (SQLException failure) {
            throw new IllegalStateException("校验索引清理领取失败", failure);
        }
    }

    /** 仍持有协调器时完成受保护 live target 的处理。 */
    int completeProtected(Connection connection, CleanupClaim claim) {
        return complete(connection, claim);
    }

    /** 外部删除后使用的 owner/token/lease CAS。 */
    int complete(Connection connection, CleanupClaim claim) {
        return update(connection, COMPLETE_SQL, claim.id(), claim.owner(), claim.token());
    }

    void stage(Connection connection, Set<String> live, String target, String owner, String token) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(live, "当前别名成员不能为空");
        requireLeaseIdentity(owner, token);
        for (String index : live) {
            if (index == null || index.isBlank() || index.equals(target) || index.equals("campus-listing-000001")) continue;
            update(connection, """
                INSERT INTO search_index_cleanup_task(id,index_name,status,owner_id,claim_token,lease_until,attempt_count,available_at,created_at)
                VALUES (?,?, 'BUILDING',?,?,TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE status='BUILDING',owner_id=VALUES(owner_id),claim_token=VALUES(claim_token),
                  lease_until=VALUES(lease_until),available_at=CURRENT_TIMESTAMP(6)
                """, UUID.randomUUID().toString(), index, owner, token);
        }
    }

    void arm(Connection connection, Set<String> staged, Set<String> stillLive, String owner, String token) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(staged, "待清理索引不能为空");
        Objects.requireNonNull(stillLive, "当前别名成员不能为空");
        requireLeaseIdentity(owner, token);
        for (String index : staged) {
            if (index == null || index.isBlank() || stillLive.contains(index)) continue;
            update(connection, """
                UPDATE search_index_cleanup_task SET status='NEW',available_at=CURRENT_TIMESTAMP(6),
                  owner_id=NULL,claim_token=NULL,lease_until=NULL
                WHERE index_name=? AND status='BUILDING' AND owner_id=? AND claim_token=?
                """, index, owner, token);
        }
    }

    void recover(Connection connection, Set<String> stillLive, String owner, String token) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(stillLive, "当前别名成员不能为空");
        requireLeaseIdentity(owner, token);
        Set<String> staged = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT index_name FROM search_index_cleanup_task WHERE status='BUILDING' AND owner_id=? AND claim_token=?")) {
            statement.setString(1, owner);
            statement.setString(2, token);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) staged.add(result.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取中断切换清理状态失败", failure);
        }
        arm(connection, staged, stillLive, owner, token);
    }

    void cancel(Connection connection, String index) {
        Objects.requireNonNull(connection, "连接不能为空");
        if (index == null || index.isBlank()) return;
        update(connection, "UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,last_error=NULL,failure_class=NULL WHERE index_name=?", index);
    }

    void arm(Connection connection, String index) {
        Objects.requireNonNull(connection, "连接不能为空");
        if (index == null || index.isBlank()) return;
        update(connection, "UPDATE search_index_cleanup_task SET status='NEW',available_at=CURRENT_TIMESTAMP(6),owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE index_name=? AND status='BUILDING'", index);
    }

    String status(Connection connection, String index) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT status FROM search_index_cleanup_task WHERE index_name=?")) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取索引清理状态失败", failure);
        }
    }

    public void schedule(String index) {
        if (index == null || index.isBlank() || index.startsWith("campus-listing-000001")) return;
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,attempt_count,available_at,created_at) VALUES (?,?, 'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status=IF(status='DONE',status,'NEW'),available_at=IF(status='DONE',available_at,CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), index);
    }

    void schedule(Connection connection, String index) {
        if (index == null || index.isBlank() || index.startsWith("campus-listing-000001")) return;
        update(connection, """
            INSERT INTO search_index_cleanup_task(id,index_name,status,attempt_count,available_at,created_at)
            VALUES (?,?, 'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE status=IF(status='DONE',status,'NEW'),
              available_at=IF(status='DONE',available_at,CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), index);
    }

    public void registerBuilding(String index, String owner, String token) {
        if (index == null || index.isBlank() || index.startsWith("campus-listing-000001")) return;
        requireLeaseIdentity(owner, token);
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,owner_id,claim_token,lease_until,attempt_count,available_at,created_at) VALUES (?,?, 'BUILDING',?,?,TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='BUILDING',owner_id=VALUES(owner_id),claim_token=VALUES(claim_token),lease_until=VALUES(lease_until)", UUID.randomUUID().toString(), index, owner, token);
    }

    public boolean renewBuilding(String index, String owner) {
        if (index == null || index.isBlank()) return false;
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("清理租约 owner 不能为空");
        return jdbc.update("UPDATE search_index_cleanup_task SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE index_name=? AND status='BUILDING' AND owner_id=? AND lease_until > CURRENT_TIMESTAMP(6)", index, owner) == 1;
    }

    public void arm(String index) {
        if (index == null || index.isBlank()) return;
        jdbc.update("UPDATE search_index_cleanup_task SET status='NEW',available_at=CURRENT_TIMESTAMP(6),owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE index_name=? AND status='BUILDING'", index);
    }

    public void cancel(String index) {
        if (index == null || index.isBlank()) return;
        jdbc.update("UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,last_error=NULL,failure_class=NULL WHERE index_name=?", index);
    }

    private static void requireLeaseIdentity(String owner, String token) {
        if (owner == null || owner.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("清理租约身份不能为空");
        }
    }

    private static int update(Connection connection, String sql, Object... values) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新索引清理状态失败", failure);
        }
    }

    private static boolean permanentFailure(Throwable failure) {
        if (failure instanceof ElasticsearchProductSearch.SearchUnavailableException unavailable) {
            return unavailable.getCause() instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException elastic
                && (elastic.status() == 400 || elastic.status() == 403 || elastic.status() == 409);
        }
        if (failure instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException elastic) {
            return elastic.status() == 400 || elastic.status() == 403 || elastic.status() == 409;
        }
        return false;
    }

    private record ClaimSeed(String id, String indexName, int attemptCount) { }

    public record CleanupClaim(String id, String indexName, int attemptCount,
                               String owner, String token, Timestamp leaseUntil) {
        public CleanupClaim {
            Objects.requireNonNull(id, "清理领取ID不能为空");
            Objects.requireNonNull(indexName, "清理索引不能为空");
            Objects.requireNonNull(owner, "清理领取 owner 不能为空");
            Objects.requireNonNull(token, "清理领取 token 不能为空");
            Objects.requireNonNull(leaseUntil, "清理租约不能为空");
            if (id.isBlank() || indexName.isBlank() || owner.isBlank() || token.isBlank() || attemptCount <= 0) {
                throw new IllegalArgumentException("清理领取参数无效");
            }
        }
    }
}
