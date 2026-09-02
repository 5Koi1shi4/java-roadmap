package com.example.campusmarket.catalog.search;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/** Serializes cross-instance alias work with a MySQL session lock. */
@Component
public final class SearchAliasCoordinator {
    static final String LOCK_NAME = "campus-market:search-alias-coordination";
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);

    private final DataSource dataSource;

    public SearchAliasCoordinator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "数据源不能为空");
    }

    public <T> T execute(Duration timeout, CriticalSection<T> section) {
        validateTimeout(timeout);
        Objects.requireNonNull(section, "临界区不能为空");

        Throwable primary = null;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            int timeoutSeconds = timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1) > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) (timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1));
            if (queryLock(connection, timeoutSeconds) != 1) {
                throw new SearchCoordinationException("获取搜索别名协调锁超时");
            }
            try {
                return section.run(connection);
            } catch (Throwable failure) {
                primary = failure;
                return SearchAliasCoordinator.<T, RuntimeException>rethrow(failure);
            } finally {
                release(connection, primary);
            }
        } catch (Throwable failure) {
            return SearchAliasCoordinator.rethrow(failure);
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
                return result.getInt(1);
            }
        }
    }

    private static void release(Connection connection, Throwable primary) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SearchCoordinationException("释放搜索别名协调锁失败");
                }
            }
        } catch (Throwable releaseFailure) {
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
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
