package com.example.campusmarket.product.infrastructure;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 读侧索引重建门禁；所有租约和 fencing 判断都以 product_read_db 的时间为准。
 */
@Repository
public class ProductRebuildGateRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate renewalTransaction;

    public ProductRebuildGateRepository(JdbcTemplate jdbc,
                                        PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.renewalTransaction = new TransactionTemplate(transactionManager);
        this.renewalTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 尝试取得重建租约；仍有有效租约时不会覆盖当前 owner。 */
    public Optional<GateClaim> acquire(String ownerId, Duration lease) {
        validateOwner(ownerId);
        long leaseMicros = micros(lease, false);
        Optional<GateClaim> acquired = transaction.execute(status -> {
            GateRow current = currentGate(true);
            // 过期的重建必须先核对 ES alias 与持久清理事实，不能直接擦掉目标索引。
            if ("REBUILDING".equals(current.mode())) {
                return Optional.empty();
            }

            long generation = Math.addExact(current.generation(), 1L);
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                UPDATE product_rebuild_gate
                SET mode='REBUILDING', intent='BUILDING', rebuild_index=NULL,
                    snapshot_sequence_no=0, cutover_sequence_no=0,
                    generation=?, owner_id=?, claim_token=?,
                    lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                    updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=1
                """, generation, ownerId, token, leaseMicros);
            if (changed != 1) {
                throw new IllegalStateException("重建门禁更新失败");
            }
            Timestamp leaseUntil = jdbc.queryForObject(
                    "SELECT lease_until FROM product_rebuild_gate WHERE id=1",
                    Timestamp.class);
            if (leaseUntil == null) {
                throw new IllegalStateException("重建门禁租约缺失");
            }
            return Optional.of(new GateClaim(ownerId, token, generation, leaseUntil.toInstant()));
        });
        return acquired == null ? Optional.empty() : acquired;
    }

    /** 正常索引 dispatcher 领取前调用；数据库故障按关闭门禁处理。 */
    public boolean isOpenForIndexing() {
        try {
            String mode = jdbc.queryForObject(
                    "SELECT mode FROM product_rebuild_gate WHERE id=1", String.class);
            return "OPEN".equals(mode);
        } catch (EmptyResultDataAccessException failure) {
            return false;
        }
    }

    /** 门禁打开且任务属于当前或更早代时允许清理旧索引。 */
    public boolean isOpenForGeneration(long generation) {
        if (generation <= 0) {
            return false;
        }
        try {
            GateRow current = jdbc.queryForObject("""
                SELECT mode,generation,lease_until
                FROM product_rebuild_gate
                WHERE id=1
                """, (result, row) -> {
                    Timestamp leaseUntil = result.getTimestamp("lease_until");
                    return new GateRow(result.getString("mode"), result.getLong("generation"),
                            leaseUntil == null ? null : leaseUntil.toInstant());
                });
            return current != null && "OPEN".equals(current.mode())
                    && generation <= current.generation();
        } catch (EmptyResultDataAccessException failure) {
            return false;
        }
    }

    /** 记录一致性快照的 sequence 高水位和正在构建的具体索引。 */
    public boolean recordSnapshot(GateClaim claim, String rebuildIndex, long sequenceNo) {
        requireClaim(claim);
        if (rebuildIndex == null || rebuildIndex.isBlank() || rebuildIndex.length() > 200
                || sequenceNo < 0) {
            throw new IllegalArgumentException("重建快照参数无效");
        }
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_rebuild_gate
            SET intent='BUILDING',rebuild_index=?,snapshot_sequence_no=?,cutover_sequence_no=?
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, rebuildIndex, sequenceNo, sequenceNo, claim.ownerId(), claim.claimToken(),
                claim.generation()) == 1);
    }

    /** 在创建 ES 索引前先保存其确定名称，关闭建索引和快照之间的崩溃窗口。 */
    public boolean recordTarget(GateClaim claim, String rebuildIndex) {
        requireClaim(claim);
        if (rebuildIndex == null || rebuildIndex.isBlank() || rebuildIndex.length() > 200) {
            throw new IllegalArgumentException("重建目标索引无效");
        }
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_rebuild_gate
            SET rebuild_index=?,updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND mode='REBUILDING' AND intent='BUILDING'
              AND rebuild_index IS NULL AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, rebuildIndex, claim.ownerId(), claim.claimToken(), claim.generation()) == 1);
    }

    /** 领取过期同一代重建以核对别名；只替换 owner/token，不改 intent/target。 */
    public Optional<RecoveryClaim> claimExpiredRecovery(String ownerId, Duration lease) {
        validateOwner(ownerId);
        long leaseMicros = micros(lease, false);
        Optional<RecoveryClaim> result = transaction.execute(status -> {
            RecoveryRow row = jdbc.queryForObject("""
                SELECT mode,intent,rebuild_index,generation,lease_until
                FROM product_rebuild_gate WHERE id=1 FOR UPDATE
                """, (rs, number) -> new RecoveryRow(rs.getString("mode"),
                    rs.getString("intent"), rs.getString("rebuild_index"),
                    rs.getLong("generation"), rs.getTimestamp("lease_until")));
            if (row == null || !"REBUILDING".equals(row.mode()) || row.leaseUntil() == null
                    || row.leaseUntil().toInstant().isAfter(databaseNow())) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                UPDATE product_rebuild_gate SET owner_id=?,claim_token=?,
                    lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                    updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=1 AND mode='REBUILDING' AND generation=?
                """, ownerId, token, leaseMicros, row.generation());
            if (changed != 1) {
                throw new IllegalStateException("过期重建恢复领取失败");
            }
            Timestamp until = jdbc.queryForObject(
                    "SELECT lease_until FROM product_rebuild_gate WHERE id=1", Timestamp.class);
            if (until == null) {
                throw new IllegalStateException("重建恢复租约缺失");
            }
            return Optional.of(new RecoveryClaim(
                    new GateClaim(ownerId, token, row.generation(), until.toInstant()),
                    row.intent(), row.rebuildIndex()));
        });
        return result == null ? Optional.empty() : result;
    }

    /** 标记已完成高水位补放，供别名切换前后审计并继续 fencing。 */
    public boolean markCutover(GateClaim claim, long sequenceNo) {
        requireClaim(claim);
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("切换高水位无效");
        }
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_rebuild_gate
            SET intent='CUTOVER',cutover_sequence_no=?
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
              AND snapshot_sequence_no <= ?
            """, sequenceNo, claim.ownerId(), claim.claimToken(), claim.generation(), sequenceNo) == 1);
    }

    /** 续租独立提交，使只读一致性快照尚未结束时租约仍对其他连接可见。 */
    public boolean renew(GateClaim claim, Duration lease) {
        requireClaim(claim);
        long leaseMicros = micros(lease, false);
        return renewalTransaction.execute(status -> jdbc.update("""
            UPDATE product_rebuild_gate
            SET lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, leaseMicros, claim.ownerId(), claim.claimToken(), claim.generation()) == 1);
    }

    /** 别名切换前的最后一次 ownership 检查。 */
    public boolean isOwned(GateClaim claim) {
        requireClaim(claim);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM product_rebuild_gate
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, Integer.class, claim.ownerId(), claim.claimToken(), claim.generation());
        return count != null && count == 1;
    }

    /** 完成切换后打开门禁；更新条件包含 owner/token/generation/租约 fencing。 */
    public boolean finish(GateClaim claim) {
        requireClaim(claim);
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_rebuild_gate
            SET mode='OPEN',intent='NONE',rebuild_index=NULL,
                owner_id=NULL,claim_token=NULL,lease_until=NULL,
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.ownerId(), claim.claimToken(), claim.generation()) == 1);
    }

    /** 构建失败时释放当前租约，旧 owner 无法释放新一代门禁。 */
    public boolean release(GateClaim claim) {
        requireClaim(claim);
        return transaction.execute(status -> jdbc.update("""
            UPDATE product_rebuild_gate
            SET mode='OPEN',intent='NONE',rebuild_index=NULL,
                snapshot_sequence_no=0,cutover_sequence_no=0,
                owner_id=NULL,claim_token=NULL,lease_until=NULL,
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=?
            """, claim.ownerId(), claim.claimToken(), claim.generation()) == 1);
    }

    private GateRow currentGate(boolean forUpdate) {
        String sql = "SELECT mode,generation,lease_until FROM product_rebuild_gate WHERE id=1"
                + (forUpdate ? " FOR UPDATE" : "");
        try {
            return jdbc.queryForObject(sql, (result, row) -> {
                Timestamp leaseUntil = result.getTimestamp("lease_until");
                return new GateRow(result.getString("mode"), result.getLong("generation"),
                        leaseUntil == null ? null : leaseUntil.toInstant());
            });
        } catch (EmptyResultDataAccessException failure) {
            throw new IllegalStateException("重建门禁记录缺失", failure);
        }
    }

    private Instant databaseNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        if (now == null) {
            throw new IllegalStateException("数据库时间缺失");
        }
        return now.toInstant();
    }

    private static void requireClaim(GateClaim claim) {
        Objects.requireNonNull(claim, "重建门禁领取记录不能为空");
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 100) {
            throw new IllegalArgumentException("重建 owner 无效");
        }
    }

    private static long micros(Duration duration, boolean allowZero) {
        Objects.requireNonNull(duration, "租约不能为空");
        if (duration.isNegative() || (!allowZero && duration.isZero())) {
            throw new IllegalArgumentException("租约必须为正数");
        }
        try {
            long value = duration.toNanos() / 1_000;
            if (value == 0 && !duration.isZero()) {
                return 1;
            }
            return value;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("租约过大", overflow);
        }
    }

    private record GateRow(String mode, long generation, Instant leaseUntil) {
    }

    private record RecoveryRow(String mode, String intent, String rebuildIndex,
                               long generation, Timestamp leaseUntil) {
    }

    public record RecoveryClaim(GateClaim claim, String intent, String rebuildIndex) {
    }

    public record GateClaim(String ownerId, String claimToken, long generation, Instant leaseUntil) {
        public GateClaim {
            if (ownerId == null || ownerId.isBlank() || claimToken == null || claimToken.isBlank()
                    || generation <= 0 || leaseUntil == null) {
                throw new IllegalArgumentException("重建门禁领取记录无效");
            }
        }
    }
}
