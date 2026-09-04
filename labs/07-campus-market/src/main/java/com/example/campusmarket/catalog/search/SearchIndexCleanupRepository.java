package com.example.campusmarket.catalog.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short transactional claims and owner/token-fenced cleanup state changes. */
@Repository
public class SearchIndexCleanupRepository {
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
        return jdbc.update("""
            UPDATE search_index_cleanup_task
            SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                last_error=NULL,failure_class=NULL
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.id(), claim.owner(), claim.token());
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

    /** Owner/token check used inside the coordinator-held critical section. */
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

    /** Complete a live protected target while still holding the coordinator. */
    int completeProtected(Connection connection, CleanupClaim claim) {
        return complete(connection, claim);
    }

    /** Owner/token/lease CAS used after an external delete. */
    int complete(Connection connection, CleanupClaim claim) {
        return update(connection, """
            UPDATE search_index_cleanup_task
            SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                last_error=NULL,failure_class=NULL
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.id(), claim.owner(), claim.token());
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
