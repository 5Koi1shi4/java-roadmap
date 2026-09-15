package com.example.campusmarket.product.infrastructure;

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

/** 读侧索引待办的数据库时间领取与 owner/token fencing。 */
@Repository
public final class ProductIndexOutboxClaimer {
    private static final RowMapper<OutboxRow> ROW_MAPPER = ProductIndexOutboxClaimer::mapRow;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ProductIndexOutboxClaimer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    /** 领取 NEW 或已过期 PUBLISHING 待办；租约起止均以 MySQL 时间为准。 */
    public List<Claim> claimBatch(String ownerId, int limit, Duration lease) {
        validateOwner(ownerId);
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("领取批次无效");
        }
        long leaseMicros = micros(lease, false);
        List<Claim> claims = transaction.execute(status -> {
            List<OutboxRow> candidates = jdbc.query("""
                SELECT id,listing_id,aggregate_version
                FROM product_index_outbox
                WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                   OR (status='PUBLISHING' AND lease_until < CURRENT_TIMESTAMP(6))
                ORDER BY created_at,id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, ROW_MAPPER, limit);
            List<Claim> claimed = new ArrayList<>(candidates.size());
            for (OutboxRow candidate : candidates) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("""
                    UPDATE product_index_outbox
                    SET status='PUBLISHING',owner_id=?,claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                        attempt_count=attempt_count+1
                    WHERE id=?
                      AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                           OR (status='PUBLISHING' AND lease_until < CURRENT_TIMESTAMP(6)))
                    """, ownerId, token, leaseMicros, candidate.id());
                if (changed != 1) {
                    continue;
                }
                Timestamp leaseUntil = jdbc.queryForObject(
                        "SELECT lease_until FROM product_index_outbox WHERE id=?",
                        Timestamp.class, candidate.id());
                if (leaseUntil == null) {
                    throw new IllegalStateException("索引待办租约缺失");
                }
                claimed.add(new Claim(candidate.id(), candidate.listingId(), candidate.aggregateVersion(),
                        ownerId, token, leaseUntil.toInstant()));
            }
            return claimed;
        });
        return claims == null ? List.of() : List.copyOf(claims);
    }

    /** 只有当前 owner 和 token 才能把待办标记为已发布。 */
    public boolean markPublished(Claim claim) {
        Objects.requireNonNull(claim, "领取记录不能为空");
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_index_outbox
            SET status='PUBLISHED',owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, claim.id(), claim.ownerId(), claim.claimToken()) == 1);
    }

    /** 只有当前 owner 和 token 才能释放重试，旧 owner 的迟到结果会被 fencing。 */
    public boolean markRetry(Claim claim, Duration delay) {
        Objects.requireNonNull(claim, "领取记录不能为空");
        long delayMicros = micros(delay, true);
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_index_outbox
            SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                available_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, delayMicros, claim.id(), claim.ownerId(), claim.claimToken()) == 1);
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 100) {
            throw new IllegalArgumentException("领取者无效");
        }
    }

    private static long micros(Duration duration, boolean allowZero) {
        Objects.requireNonNull(duration, "时间间隔不能为空");
        if (duration.isNegative() || (!allowZero && duration.isZero())) {
            throw new IllegalArgumentException("时间间隔无效");
        }
        try {
            long value = duration.toNanos() / 1_000;
            if (value == 0 && !duration.isZero()) {
                return 1;
            }
            return value;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("时间间隔过大", overflow);
        }
    }

    private static OutboxRow mapRow(ResultSet result, int rowNum) throws SQLException {
        return new OutboxRow(result.getString("id"), result.getString("listing_id"),
                result.getLong("aggregate_version"));
    }

    private record OutboxRow(String id, String listingId, long aggregateVersion) {
    }

    public record Claim(String id, String listingId, long aggregateVersion, String ownerId,
                        String claimToken, Instant leaseUntil) {
        public Claim {
            if (id == null || id.isBlank() || listingId == null || listingId.isBlank()
                    || aggregateVersion <= 0 || ownerId == null || ownerId.isBlank()
                    || claimToken == null || claimToken.isBlank() || leaseUntil == null) {
                throw new IllegalArgumentException("索引待办领取记录无效");
            }
        }
    }
}
