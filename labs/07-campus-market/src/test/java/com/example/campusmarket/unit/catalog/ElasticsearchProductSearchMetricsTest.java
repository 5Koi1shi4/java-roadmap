package com.example.campusmarket.unit.catalog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.campusmarket.catalog.search.ElasticsearchProductSearch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchProductSearchMetricsTest {
    @Test
    void initializationCoordinatorUsesProvidedMeterRegistry() throws Exception {
        MeterRegistry metrics = new SimpleMeterRegistry();
        ElasticsearchProductSearch search = new ElasticsearchProductSearch(
            mock(ElasticsearchClient.class), new JdbcTemplate(proxy(DataSource.class)), metrics);
        Field coordinatorField = ElasticsearchProductSearch.class.getDeclaredField("coordinator");
        coordinatorField.setAccessible(true);
        Object coordinator = coordinatorField.get(search);
        Field metricsField = coordinator.getClass().getDeclaredField("metrics");
        metricsField.setAccessible(true);
        assertThat(metricsField.get(coordinator)).isSameAs(metrics);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (instance, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
