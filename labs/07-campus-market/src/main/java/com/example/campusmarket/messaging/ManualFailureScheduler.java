package com.example.campusmarket.messaging;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 人工失败副本的有界、可接管补偿调度器。 */
@Service
@Profile("!test")
public class ManualFailureScheduler {
    private final JdbcTemplate jdbc;
    private final ManualFailurePublisher publisher;
    private final TransactionTemplate transactions;

    public ManualFailureScheduler(JdbcTemplate jdbc, ManualFailurePublisher publisher,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
        this.publisher = Objects.requireNonNull(publisher, "人工发布器不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("批量大小必须在1到100之间");
        String owner = "manual-" + UUID.randomUUID();
        List<ManualFailure> failures = claimWithRetry(owner, limit, Duration.ofSeconds(30));
        int completed = 0;
        for (ManualFailure failure : failures) {
            // attempt_count=3 means the previous owner may have crashed after
            // claiming.  Reclaiming it must converge to a terminal state and
            // must never perform a fourth publish.
            if (failure.attemptCount() >= 3) {
                failExhausted(failure.id(), failure.ownerId(), failure.claimToken());
                continue;
            }
            try {
                publisher.publish(failure);
                completed += complete(failure.id(), failure.ownerId(), failure.claimToken());
            } catch (RuntimeException ignored) {
                release(failure.id(), failure.ownerId(), failure.claimToken());
            }
        }
        return completed;
    }

    /** 暴露给集成测试及调度适配器的领取操作；每次领取都有随机 token。 */
    @Transactional
    public List<ManualFailure> claimBatch(String owner, int limit, Duration lease) {
        requireOwner(owner);
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("批量大小必须在1到100之间");
        long leaseMicros = durationMicros(lease);
        List<ManualFailure> claimed = new ArrayList<>();
        jdbc.query("""
            SELECT id,source_type,source_id,consumer_name,failure_class,payload,attempt_count,status
            FROM manual_failure
            WHERE ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
              AND ((status='NEW' AND attempt_count < 3)
                OR (status='PUBLISHING' AND attempt_count <= 3))
            ORDER BY available_at,id LIMIT ? FOR UPDATE SKIP LOCKED
            """, rs -> {
                UUID id = UUID.fromString(rs.getString("id"));
                String token = UUID.randomUUID().toString();
                int previousAttempts = rs.getInt("attempt_count");
                boolean exhausted = "PUBLISHING".equals(rs.getString("status")) && previousAttempts >= 3;
                int changed = jdbc.update("""
                    UPDATE manual_failure
                    SET status='PUBLISHING', owner_id=?, claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        attempt_count=attempt_count+CASE WHEN status='NEW' THEN 1 ELSE 0 END
                    WHERE id=? AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6) AND attempt_count < 3)
                        OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count <= 3))
                    """, owner, token, leaseMicros, id.toString());
                if (changed == 1) {
                    Instant leaseUntil = jdbc.queryForObject(
                        "SELECT lease_until FROM manual_failure WHERE id=? AND owner_id=? AND claim_token=?",
                        (result, row) -> result.getTimestamp(1).toInstant(), id.toString(), owner, token);
                    claimed.add(new ManualFailure(id, rs.getString("source_type"),
                        UUID.fromString(rs.getString("source_id")), rs.getString("consumer_name"),
                        rs.getString("failure_class"), rs.getString("payload"), owner, token,
                        leaseUntil, exhausted ? previousAttempts : previousAttempts + 1));
                }
            }, limit);
        return List.copyOf(claimed);
    }

    public int complete(UUID id, String owner, String token) {
        Objects.requireNonNull(id, "id 不能为空");
        requireOwner(owner);
        Objects.requireNonNull(token, "claim token 不能为空");
        return transactions.execute(status -> jdbc.update("""
            UPDATE manual_failure
            SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(6), owner_id=NULL,
                claim_token=NULL, lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, id.toString(), owner, token));
    }

    /** 受控事务终结崩溃后已消耗三次尝试的副本；旧 owner/token 无法改变状态。 */
    public int failExhausted(UUID id, String owner, String token) {
        Objects.requireNonNull(id, "id 不能为空");
        requireOwner(owner);
        Objects.requireNonNull(token, "claim token 不能为空");
        return transactions.execute(status -> jdbc.update("""
            UPDATE manual_failure
            SET status='FAILED', owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND attempt_count >= 3 AND owner_id=? AND claim_token=?
            """, id.toString(), owner, token));
    }

    protected int release(UUID id, String owner, String token) {
        return transactions.execute(status -> jdbc.update("""
            UPDATE manual_failure
            SET status=CASE WHEN attempt_count < 3 THEN 'NEW' ELSE 'FAILED' END,
                owner_id=NULL, claim_token=NULL, lease_until=NULL,
                available_at=TIMESTAMPADD(MICROSECOND, 1000000, CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, id.toString(), owner, token));
    }

    private List<ManualFailure> claimWithRetry(String owner, int limit, Duration lease) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                List<ManualFailure> claimed = transactions.execute(status -> claimBatch(owner, limit, lease));
                return claimed == null ? List.of() : claimed;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == 1) return List.of();
            }
        }
        return List.of();
    }

    private static long durationMicros(Duration duration) {
        Objects.requireNonNull(duration, "租约不能为空");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("租约必须为正数");
        try {
            long micros = Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000L);
            if (micros <= 0) throw new IllegalArgumentException("租约精度不足一微秒");
            return micros;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("租约溢出", e);
        }
    }

    private static void requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner 不能为空");
        if (owner.isBlank() || owner.length() > 100) throw new IllegalArgumentException("owner 无效");
    }

    public record ManualFailure(UUID id, String sourceType, UUID sourceId, String consumerName,
                                String failureClass, String payload, String ownerId, String claimToken,
                                Instant leaseUntil, int attemptCount) {
        public ManualFailure {
            Objects.requireNonNull(id, "id 不能为空");
            Objects.requireNonNull(sourceType, "sourceType 不能为空");
            Objects.requireNonNull(sourceId, "sourceId 不能为空");
            Objects.requireNonNull(consumerName, "consumerName 不能为空");
            Objects.requireNonNull(failureClass, "failureClass 不能为空");
            Objects.requireNonNull(payload, "payload 不能为空");
            Objects.requireNonNull(ownerId, "owner 不能为空");
            Objects.requireNonNull(claimToken, "claim token 不能为空");
            Objects.requireNonNull(leaseUntil, "租约不能为空");
            if (attemptCount <= 0) throw new IllegalArgumentException("attemptCount 无效");
        }
    }
}
