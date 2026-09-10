package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL 协调的跨实例搜索写入门禁和 generation fencing。 */
@Repository
@Profile("!test")
public class SearchGateRepository {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final Optional<SearchAliasCoordinator> coordinator;

    public SearchGateRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.coordinator = Optional.ofNullable(jdbc.getDataSource()).map(SearchAliasCoordinator::new);
    }

    @Autowired
    public SearchGateRepository(JdbcTemplate jdbc, SearchAliasCoordinator coordinator) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.coordinator = Optional.of(Objects.requireNonNull(coordinator, "协调器不能为空"));
    }

    /** 在商品事实事务内加行锁，门禁开启后才允许写 search outbox。 */
    public void assertWritable() {
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1 FOR UPDATE", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public void assertProjectionOpen() {
        // 投影 IO 不得持有门禁行锁。请求前由 ElasticsearchProductSearch 解析具体写索引，
        // 因此并发别名切换最多只会在即将清理的旧具体索引上留下过期写入。
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    /** 仅领取阶段的 fence：可在短数据库领取事务中持有。 */
    public void assertProjectionOpenForClaim() {
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1 FOR UPDATE", String.class);
        if (!"OPEN".equals(mode)) throw new SearchGateClosedException();
    }

    public Lease acquire(String owner, Duration leaseDuration) {
        return coordinator().execute(COORDINATION_TIMEOUT, "gate-acquire", owner,
            connection -> acquire(connection, owner, leaseDuration));
    }

    public boolean renew(Lease lease, Duration extension) {
        Objects.requireNonNull(lease, "门禁租约不能为空");
        if (extension == null || extension.isNegative() || extension.isZero()) throw new IllegalArgumentException("续租时长无效");
        long micros = Math.addExact(Math.multiplyExact(extension.getSeconds(), 1_000_000L), extension.getNano() / 1_000L);
        return jdbc.update("UPDATE search_rebuild_gate SET lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=? AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)", micros, lease.owner(), lease.token(), lease.generation()) == 1;
    }

    /** 外部写入前立即检查完整租约身份。 */
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
        return coordinator().execute(COORDINATION_TIMEOUT, "gate-release", lease.owner(),
            connection -> release(connection, lease));
    }

    private SearchAliasCoordinator coordinator() {
        return coordinator.orElseThrow(() -> new IllegalStateException("门禁协调操作需要配置数据源"));
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
        // 只要完整 owner/token/generation 身份仍匹配，释放过期租约也是安全的。
        // 接管会改变该身份，因此旧 owner 不能清除新 owner 的租约。
        try (PreparedStatement statement = connection.prepareStatement("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND mode='REBUILDING' AND owner_id=? AND claim_token=? AND generation=?")) {
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
