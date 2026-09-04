package com.example.campusmarket.catalog.search;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 使用 MySQL 会话锁串行化跨实例别名操作。 */
@Component
public final class SearchAliasCoordinator {
    static final String LOCK_NAME = "campus-market:search-alias-coordination";
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAliasCoordinator.class);

    private final DataSource dataSource;
    private final MeterRegistry metrics;
    private final Runnable acquisitionAttemptHook;

    public SearchAliasCoordinator(DataSource dataSource) {
        this(dataSource, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), () -> { });
    }

    @Autowired
    public SearchAliasCoordinator(DataSource dataSource, MeterRegistry metrics) {
        this(dataSource, metrics, () -> { });
    }

    /** 仅测试使用的 GET_LOCK 尝试通知；默认无操作，不改变锁协议。 */
    SearchAliasCoordinator(DataSource dataSource, MeterRegistry metrics, Runnable acquisitionAttemptHook) {
        this.dataSource = Objects.requireNonNull(dataSource, "数据源不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标注册表不能为空");
        this.acquisitionAttemptHook = Objects.requireNonNull(acquisitionAttemptHook, "获取尝试 hook 不能为空");
    }

    public <T> T execute(Duration timeout, CriticalSection<T> section) {
        return execute(timeout, "unknown", "unknown", section);
    }

    DataSource dataSource() {
        return dataSource;
    }

    /** 在固定物理连接上取得协调锁并执行临界区。 */
    public <T> T execute(Duration timeout, String operation, String owner, CriticalSection<T> section) {
        validateTimeout(timeout);
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("协调操作不能为空");
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("协调 owner 不能为空");
        Objects.requireNonNull(section, "临界区不能为空");

        Throwable primary = null;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            int timeoutSeconds = timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1) > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) (timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1));
            long waitStarted = System.nanoTime();
            int lockResult;
            try {
                acquisitionAttemptHook.run();
                lockResult = queryLock(connection, timeoutSeconds);
            } catch (Throwable failure) {
                recordAcquire(operation, owner, System.nanoTime() - waitStarted);
                throw toRuntime(failure, context(operation, owner, "获取搜索别名协调锁失败"));
            }
            long waitedNanos = System.nanoTime() - waitStarted;
            recordAcquire(operation, owner, waitedNanos);
            if (lockResult != 1) {
                if (lockResult == 0) {
                    metrics.counter("search.alias.coordination.lock.timeout", "lock", LOCK_NAME, "operation", operation).increment();
                    throw new SearchCoordinationTimeoutException(context(operation, owner, "获取搜索别名协调锁超时"), waitedNanos);
                }
                throw new SearchCoordinationException(context(operation, owner, "获取搜索别名协调锁返回无效结果"));
            }
            try {
                return section.run(connection);
            } catch (Throwable failure) {
                RuntimeException normalized = toRuntime(failure, "搜索别名临界区执行失败");
                primary = normalized;
                return SearchAliasCoordinator.<T, RuntimeException>rethrow(normalized);
            } finally {
                release(connection, primary, operation, owner);
            }
        } catch (Throwable failure) {
            return SearchAliasCoordinator.<T, RuntimeException>rethrow(toRuntime(failure, "搜索别名协调失败"));
        }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("协调锁等待时长必须大于0且不超过60秒");
        }
    }

    private static int queryLock(Connection connection, int timeoutSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)");) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SearchCoordinationException("获取搜索别名协调锁无返回结果");
                int lockResult = result.getInt(1);
                return result.wasNull() ? -1 : lockResult;
            }
        }
    }

    private void release(Connection connection, Throwable primary, String operation, String owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SearchCoordinationException(context(operation, owner, "释放搜索别名协调锁失败"));
                }
            }
        } catch (Throwable releaseFailure) {
            releaseFailure = toRuntime(releaseFailure, "释放搜索别名协调锁失败");
            metrics.counter("search.alias.coordination.lock.release.failure", "lock", LOCK_NAME, "operation", operation).increment();
            LOGGER.warn("搜索别名协调锁释放失败，lockName={}, operation={}, owner={}", LOCK_NAME, operation, owner, releaseFailure);
            if (primary != null) {
                primary.addSuppressed(releaseFailure);
                return;
            }
            SearchAliasCoordinator.<Void, RuntimeException>rethrow(releaseFailure);
        }
    }

    @FunctionalInterface
    public interface CriticalSection<T> {
        T run(Connection connection) throws Exception;
    }

    public static class SearchCoordinationException extends RuntimeException {
        public SearchCoordinationException(String message) { super(message); }
        public SearchCoordinationException(String message, Throwable cause) { super(message, cause); }
    }

    /** GET_LOCK 在限定等待时间内未取得锁的可重试异常。 */
    public static final class SearchCoordinationTimeoutException extends SearchCoordinationException {
        private final long waitedNanos;

        public SearchCoordinationTimeoutException(String message, long waitedNanos) {
            super(message);
            this.waitedNanos = waitedNanos;
        }

        public long waitedNanos() {
            return waitedNanos;
        }
    }

    private void recordAcquire(String operation, String owner, long nanos) {
        Timer.builder("search.alias.coordination.lock.acquire")
            .description("搜索别名协调锁获取耗时")
            .tags(Tags.of("lock", LOCK_NAME, "operation", operation))
            .register(metrics)
            .record(nanos, TimeUnit.NANOSECONDS);
        LOGGER.debug("搜索别名协调锁获取完成，lockName={}, operation={}, owner={}, waitMs={}",
            LOCK_NAME, operation, owner, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static String context(String operation, String owner, String message) {
        return message + "，lockName=" + LOCK_NAME + "，operation=" + operation + "，owner=" + owner;
    }

    private static RuntimeException toRuntime(Throwable failure, String message) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new SearchCoordinationException(message, failure);
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
