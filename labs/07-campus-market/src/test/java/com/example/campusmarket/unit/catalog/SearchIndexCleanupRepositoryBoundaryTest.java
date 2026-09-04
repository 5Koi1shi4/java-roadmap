package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.search.SearchIndexCleanupRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 清理状态 SQL 必须由专用仓储承担，避免搜索 ES 原语类越界持久化职责。 */
class SearchIndexCleanupRepositoryBoundaryTest {
    @Test
    void ownsCleanupPersistenceOperations() throws Exception {
        assertThat(find("stage", Connection.class, Set.class, String.class, String.class, String.class)).isNotNull();
        assertThat(find("arm", Connection.class, Set.class, Set.class, String.class, String.class)).isNotNull();
        assertThat(find("recover", Connection.class, Set.class, String.class, String.class)).isNotNull();
        assertThat(find("cancel", Connection.class, String.class)).isNotNull();
        assertThat(find("schedule", String.class)).isNotNull();
        assertThat(find("registerBuilding", String.class, String.class, String.class)).isNotNull();
        assertThat(find("renewBuilding", String.class, String.class)).isNotNull();
        assertThat(find("arm", String.class)).isNotNull();
        assertThat(find("cancel", String.class)).isNotNull();
    }

    private static Method find(String name, Class<?>... parameters) {
        try {
            Method method = SearchIndexCleanupRepository.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }
}
