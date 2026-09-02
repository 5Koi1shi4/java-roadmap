package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** MySQL 协调的跨实例搜索写入门禁和 generation fencing。 */
@Repository
public class SearchGateRepository {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final SearchAliasCoordinator coordinator;

    public SearchGateRepository(JdbcTemplate jdbc) {
        this(jdbc, jdbc.getDataSource() == null ? null : new SearchAliasCoordinator(jdbc.getDataSource()));
    }

    @Autowired
    public SearchGateRepository(JdbcTemplate jdbc, SearchAliasCoordinator coordinator) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.coordinator = coordinator;
    }

    /** 在商品事实事务内加行锁，门禁开启后才允许写 search outbox。 */
    public void assertWritable() {
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1 FOR UPDATE", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public void assertProjectionOpen() {
        // Projection IO must not hold the gate row lock. The concrete write
        // index is resolved by ElasticsearchProductSearch before the request,
        // so a concurrent alias swap can only leave a stale write on the old
        // (soon-to-be-cleaned) concrete index.
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    /** Claim-only fence: safe to hold for the short DB claim transaction. */
    public void assertProjectionOpenForClaim() {
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1 FOR UPDATE", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public Lease acquire(String owner, Duration leaseDuration) {
        return coordinator.execute(COORDINATION_TIMEOUT, connection -> acquire(connection, owner, leaseDuration));
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

    public int release(Lease lease) {
        return coordinator.execute(COORDINATION_TIMEOUT, connection -> release(connection, lease));
    }

    Lease acquire(Connection connection, String owner, Duration leaseDuration) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(owner, "owner不能为空");
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) throw new IllegalArgumentException("门禁租约无效");
        long micros = Math.addExact(Math.multiplyExact(leaseDuration.getSeconds(), 1_000_000L), leaseDuration.getNano() / 1_000L);
        String token = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("UPDATE search_rebuild_gate SET mode='REBUILDING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),generation=generation+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND (mode='OPEN' OR lease_until <= CURRENT_TIMESTAMP(6))")) {
            statement.setString(1, owner);
            statement.setString(2, token);
            statement.setLong(3, micros);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("已有重建任务运行");
        } catch (SQLException failure) {
            throw new IllegalStateException("更新门禁状态失败", failure);
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT generation,lease_until FROM search_rebuild_gate WHERE id=1 AND owner_id=? AND claim_token=?")) {
            statement.setString(1, owner);
            statement.setString(2, token);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("读取门禁租约失败");
                return new Lease(owner, token, result.getLong(1), result.getTimestamp(2));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取门禁租约失败", failure);
        }
    }

    void assertLease(Connection connection, Lease lease) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(lease, "门禁租约不能为空");
        try (PreparedStatement statement = connection.prepareStatement("SELECT generation FROM search_rebuild_gate WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=? AND generation=? AND lease_until > CURRENT_TIMESTAMP(6) FOR UPDATE")) {
            statement.setString(1, lease.owner());
            statement.setString(2, lease.token());
            statement.setLong(3, lease.generation());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != lease.generation()) throw new SearchGateClosedException();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("校验门禁租约失败", failure);
        }
    }

    int release(Connection connection, Lease lease) {
        Objects.requireNonNull(connection, "连接不能为空");
        Objects.requireNonNull(lease, "门禁租约不能为空");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=? AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)")) {
            statement.setString(1, lease.owner());
            statement.setString(2, lease.token());
            statement.setLong(3, lease.generation());
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("释放门禁状态失败", failure);
        }
    }

    GateState readState(Connection connection) {
        Objects.requireNonNull(connection, "连接不能为空");
        try (PreparedStatement statement = connection.prepareStatement("SELECT mode,owner_id,claim_token,generation,lease_until FROM search_rebuild_gate WHERE id=1");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("读取门禁状态失败");
            return new GateState(result.getString(1), result.getString(2), result.getString(3), result.getLong(4), result.getTimestamp(5));
        } catch (SQLException failure) {
            throw new IllegalStateException("读取门禁状态失败", failure);
        }
    }

    public record Lease(String owner, String token, long generation, java.sql.Timestamp leaseUntil) {
        public Lease { Objects.requireNonNull(owner); Objects.requireNonNull(token); Objects.requireNonNull(leaseUntil); if (owner.isBlank() || token.isBlank() || generation <= 0) throw new IllegalArgumentException("门禁租约无效"); }
    }
    public static class SearchGateClosedException extends RuntimeException {
        public SearchGateClosedException() { }
        public SearchGateClosedException(String message) { super(message); }
    }

    public record GateState(String mode, String owner, String token, long generation, Timestamp leaseUntil) {
        public boolean isOpen() { return "OPEN".equals(mode); }
    }
}
