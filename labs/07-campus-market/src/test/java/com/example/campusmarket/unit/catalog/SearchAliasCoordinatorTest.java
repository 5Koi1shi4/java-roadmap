package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.search.SearchAliasCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchAliasCoordinatorTest {
    @Test
    void lockTimeoutIsClassifiedAndMeasuredWithoutOwnerMetricTag() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SearchAliasCoordinator coordinator = new SearchAliasCoordinator(dataSource(0), metrics);

        SearchAliasCoordinator.SearchCoordinationTimeoutException failure = assertThrows(
            SearchAliasCoordinator.SearchCoordinationTimeoutException.class,
            () -> coordinator.execute(Duration.ofSeconds(1), "cleanup-delete", "owner-A", connection -> null));

        assertThat(failure.getMessage()).contains("cleanup-delete", "owner-A");
        assertThat(metrics.get("search.alias.coordination.lock.timeout").counter().count()).isEqualTo(1);
        assertThat(metrics.get("search.alias.coordination.lock.acquire").timer().count()).isEqualTo(1);
        assertThat(metrics.get("search.alias.coordination.lock.timeout").counter().getId().getTags())
            .noneMatch(tag -> tag.getKey().equals("owner"));
    }

    @Test
    void releaseFailureIsMeasuredAndReturnedAsCoordinationFailure() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SearchAliasCoordinator coordinator = new SearchAliasCoordinator(dataSource(1, 0), metrics);

        SearchAliasCoordinator.SearchCoordinationException failure = assertThrows(
            SearchAliasCoordinator.SearchCoordinationException.class,
            () -> coordinator.execute(Duration.ofSeconds(1), "reconcile", "owner-B", connection -> null));

        assertThat(failure.getMessage()).contains("释放");
        assertThat(metrics.get("search.alias.coordination.lock.release.failure").counter().count()).isEqualTo(1);
    }

    @Test
    void lockAcquireFailureMeasuresElapsedDurationInsteadOfAbsoluteNanoTime() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SearchAliasCoordinator coordinator = new SearchAliasCoordinator(failingAcquireDataSource(), metrics);

        assertThrows(SearchAliasCoordinator.SearchCoordinationException.class,
            () -> coordinator.execute(Duration.ofSeconds(1), "reconcile", "owner-C", connection -> null));

        assertThat(metrics.get("search.alias.coordination.lock.acquire").timer().count()).isEqualTo(1);
        assertThat(metrics.get("search.alias.coordination.lock.acquire").timer()
            .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isLessThan(1_000);
    }

    @Test
    void queryLockRuntimeFailureRetainsOriginalRuntimeException() {
        IllegalStateException original = new IllegalStateException("driver runtime failure");
        SearchAliasCoordinator coordinator = new SearchAliasCoordinator(runtimeFailingAcquireDataSource(original));
        assertThat(assertThrows(IllegalStateException.class,
            () -> coordinator.execute(Duration.ofSeconds(1), "reconcile", "owner-D", connection -> null)))
            .isSameAs(original);
    }

    @Test
    void acquireLogIncludesOwnerWithoutAddingOwnerMetricTag() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(SearchAliasCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            SimpleMeterRegistry metrics = new SimpleMeterRegistry();
            new SearchAliasCoordinator(dataSource(1), metrics)
                .execute(Duration.ofSeconds(1), "reconcile", "owner-log", connection -> null);
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("owner-log"));
            assertThat(metrics.get("search.alias.coordination.lock.acquire").timer().getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("owner"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static DataSource runtimeFailingAcquireDataSource(RuntimeException original) {
        Connection connection = (Connection) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setAutoCommit", "close" -> null;
                case "prepareStatement" -> throw original;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        return (DataSource) Proxy.newProxyInstance(SearchAliasCoordinatorTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> method.getName().equals("getConnection") ? connection : defaultValue(method.getReturnType()));
    }

    private static DataSource dataSource(int... lockResults) {
        java.util.concurrent.atomic.AtomicInteger statementNumber = new java.util.concurrent.atomic.AtomicInteger();
        Connection connection = (Connection) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setAutoCommit", "close" -> null;
                case "prepareStatement" -> statement(lockResults[Math.min(statementNumber.getAndIncrement(), lockResults.length - 1)]);
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        return (DataSource) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, args) -> method.getName().equals("getConnection") ? connection : defaultValue(method.getReturnType()));
    }

    private static DataSource failingAcquireDataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setAutoCommit", "close" -> null;
                case "prepareStatement" -> throw new java.sql.SQLException("named lock unavailable");
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        return (DataSource) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, args) -> method.getName().equals("getConnection") ? connection : defaultValue(method.getReturnType()));
    }

    private static PreparedStatement statement(int lockResult) {
        ResultSet result = (ResultSet) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{ResultSet.class},
            new java.lang.reflect.InvocationHandler() {
                private int call;
                @Override
                public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> true;
                        case "getInt" -> lockResult;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            });
        return (PreparedStatement) Proxy.newProxyInstance(
            SearchAliasCoordinatorTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> result;
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
