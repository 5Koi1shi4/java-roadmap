package com.example.campusmarket.catalog.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** MySQL 协调的跨实例搜索写入门禁和 generation fencing。 */
@Repository
public class SearchGateRepository {
    private final JdbcTemplate jdbc;

    public SearchGateRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空"); }

    /** 在商品事实事务内加行锁，门禁开启后才允许写 search outbox。 */
    public void assertWritable() {
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1 FOR UPDATE", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public void assertProjectionOpen() {
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1 FOR UPDATE", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public Lease acquire(String owner, Duration leaseDuration) {
        Objects.requireNonNull(owner, "owner不能为空");
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) throw new IllegalArgumentException("门禁租约无效");
        long micros = Math.addExact(Math.multiplyExact(leaseDuration.getSeconds(), 1_000_000L), leaseDuration.getNano() / 1_000L);
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE search_rebuild_gate SET mode='REBUILDING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),generation=generation+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND (mode='OPEN' OR lease_until <= CURRENT_TIMESTAMP(6))", owner, token, micros);
        if (changed != 1) throw new IllegalStateException("已有重建任务运行");
        return jdbc.queryForObject("SELECT generation,lease_until FROM search_rebuild_gate WHERE id=1 AND owner_id=? AND claim_token=?", (rs, rowNum) -> new Lease(owner, token, rs.getLong(1), rs.getTimestamp(2)), owner, token);
    }

    public boolean renew(Lease lease, Duration extension) {
        Objects.requireNonNull(lease, "门禁租约不能为空");
        if (extension == null || extension.isNegative() || extension.isZero()) throw new IllegalArgumentException("续租时长无效");
        long micros = Math.addExact(Math.multiplyExact(extension.getSeconds(), 1_000_000L), extension.getNano() / 1_000L);
        return jdbc.update("UPDATE search_rebuild_gate SET lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=? AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)", micros, lease.owner(), lease.token(), lease.generation()) == 1;
    }

    /** Check the complete lease identity immediately before an external write. */
    public void assertLease(Lease lease) {
        Objects.requireNonNull(lease, "门禁租约不能为空");
        Long validGeneration = jdbc.query("""
            SELECT generation FROM search_rebuild_gate
            WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            FOR UPDATE
            """, rs -> rs.next() ? rs.getLong(1) : null, lease.owner(), lease.token(), lease.generation());
        if (!Objects.equals(validGeneration, lease.generation())) throw new SearchGateClosedException();
    }

    public void release(Lease lease) {
        Objects.requireNonNull(lease, "门禁租约不能为空");
        jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=? AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)", lease.owner(), lease.token(), lease.generation());
    }

    public record Lease(String owner, String token, long generation, java.sql.Timestamp leaseUntil) {
        public Lease { Objects.requireNonNull(owner); Objects.requireNonNull(token); Objects.requireNonNull(leaseUntil); if (owner.isBlank() || token.isBlank() || generation <= 0) throw new IllegalArgumentException("门禁租约无效"); }
    }
    public static class SearchGateClosedException extends RuntimeException { }
}
