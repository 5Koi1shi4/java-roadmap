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
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public Lease acquire(String owner) {
        Objects.requireNonNull(owner, "owner不能为空");
        int changed = jdbc.update("UPDATE search_rebuild_gate SET mode='REBUILDING',owner_id=?,generation=generation+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='OPEN'", owner);
        if (changed != 1) throw new IllegalStateException("已有重建任务运行");
        Long generation = jdbc.queryForObject("SELECT generation FROM search_rebuild_gate WHERE id=1 AND owner_id=?", Long.class, owner);
        return new Lease(owner, Objects.requireNonNull(generation));
    }

    public void release(Lease lease) {
        jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND generation=?",
            lease.owner(), lease.generation());
    }

    public record Lease(String owner, long generation) {
        public Lease { Objects.requireNonNull(owner); if (owner.isBlank() || generation <= 1) throw new IllegalArgumentException("门禁租约无效"); }
    }
    public static class SearchGateClosedException extends RuntimeException { }
}
