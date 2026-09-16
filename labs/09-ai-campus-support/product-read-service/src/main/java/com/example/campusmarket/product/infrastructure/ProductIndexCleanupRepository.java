package com.example.campusmarket.product.infrastructure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 持久化重建产生的旧索引清理任务，并以 owner/token 保护并发清理。 */
@Repository
public class ProductIndexCleanupRepository {
    private static final RowMapper<CleanupRow> ROW_MAPPER = ProductIndexCleanupRepository::mapRow;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ProductIndexCleanupRepository(JdbcTemplate jdbc,
                                         PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    /** 同一具体索引只创建一条任务；重复入队保持已有任务状态。 */
    public boolean enqueue(String indexName, long generation) {
        validateIndex(indexName);
        if (generation < 0) {
            throw new IllegalArgumentException("清理代数无效");
        }
        try {
            return jdbc.update("""
                INSERT INTO product_index_cleanup_task(
                    id,index_name,generation,status)
                VALUES (?,?,?,'NEW')
                """, UUID.randomUUID().toString(), indexName, generation) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 领取 NEW 或过期 RUNNING/BUILDING 任务，租约由数据库时间产生。 */
    public List<Claim> claimBatch(String ownerId, int limit, Duration lease) {
        validateOwner(ownerId);
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("清理批次无效");
        }
        long leaseMicros = micros(lease, false);
        List<Claim> claims = transaction.execute(status -> {
            List<CleanupRow> rows = jdbc.query("""
                SELECT id,index_name,generation,attempt_count
                FROM product_index_cleanup_task
                WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                   OR (status IN ('RUNNING','BUILDING')
                       AND lease_until < CURRENT_TIMESTAMP(6))
                ORDER BY created_at,id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, ROW_MAPPER, limit);
            List<Claim> claimed = new ArrayList<>(rows.size());
            for (CleanupRow row : rows) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("""
                    UPDATE product_index_cleanup_task
                    SET status='RUNNING',owner_id=?,claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                        attempt_count=attempt_count+1
                    WHERE id=?
                      AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                           OR (status IN ('RUNNING','BUILDING')
                               AND lease_until < CURRENT_TIMESTAMP(6)))
                    """, ownerId, token, leaseMicros, row.id());
                if (changed != 1) {
                    continue;
                }
                Timestamp leaseUntil = jdbc.queryForObject(
                        "SELECT lease_until FROM product_index_cleanup_task WHERE id=?",
                        Timestamp.class, row.id());
                Integer attempt = jdbc.queryForObject(
                        "SELECT attempt_count FROM product_index_cleanup_task WHERE id=?",
                        Integer.class, row.id());
                if (leaseUntil == null || attempt == null) {
                    throw new IllegalStateException("清理任务租约缺失");
                }
                claimed.add(new Claim(row.id(), row.indexName(), row.generation(), ownerId, token,
                        leaseUntil.toInstant(), attempt));
            }
            return claimed;
        });
        return claims == null ? List.of() : List.copyOf(claims);
    }

    public boolean markDone(Claim claim) {
        requireClaim(claim);
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_index_cleanup_task
            SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                last_error=NULL,failure_class=NULL
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.id(), claim.ownerId(), claim.claimToken(), claim.generation()) == 1);
    }

    public boolean markRetry(Claim claim, Duration delay, String error) {
        requireClaim(claim);
        long delayMicros = micros(delay, true);
        String detail = truncate(error);
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_index_cleanup_task
            SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                available_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                last_error=?,failure_class='TRANSIENT'
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, delayMicros, detail, claim.id(), claim.ownerId(), claim.claimToken(),
                claim.generation()) == 1);
    }

    public boolean markFailed(Claim claim, String error) {
        requireClaim(claim);
        String detail = truncate(error);
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_index_cleanup_task
            SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                last_error=?,failure_class='PERMANENT'
            WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, detail, claim.id(), claim.ownerId(), claim.claimToken(), claim.generation()) == 1);
    }

    private static CleanupRow mapRow(ResultSet result, int rowNum) throws SQLException {
        return new CleanupRow(result.getString("id"), result.getString("index_name"),
                result.getLong("generation"), result.getInt("attempt_count"));
    }

    private static void requireClaim(Claim claim) {
        Objects.requireNonNull(claim, "清理领取记录不能为空");
    }

    private static void validateIndex(String indexName) {
        if (indexName == null || indexName.isBlank() || indexName.length() > 200) {
            throw new IllegalArgumentException("索引名称无效");
        }
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 100) {
            throw new IllegalArgumentException("清理 owner 无效");
        }
    }

    private static long micros(Duration duration, boolean allowZero) {
        Objects.requireNonNull(duration, "清理延迟不能为空");
        if (duration.isNegative() || (!allowZero && duration.isZero())) {
            throw new IllegalArgumentException("清理时间间隔无效");
        }
        try {
            long value = duration.toNanos() / 1_000;
            if (value == 0 && !duration.isZero()) {
                return 1;
            }
            return value;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("清理时间间隔过大", overflow);
        }
    }

    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "索引清理失败";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private record CleanupRow(String id, String indexName, long generation, int attemptCount) {
    }

    public record Claim(String id, String indexName, long generation, String ownerId,
                        String claimToken, Instant leaseUntil, int attemptCount) {
        public Claim {
            if (id == null || id.isBlank() || indexName == null || indexName.isBlank()
                    || generation < 0 || ownerId == null || ownerId.isBlank()
                    || claimToken == null || claimToken.isBlank() || leaseUntil == null
                    || attemptCount <= 0) {
                throw new IllegalArgumentException("清理领取记录无效");
            }
        }
    }
}
