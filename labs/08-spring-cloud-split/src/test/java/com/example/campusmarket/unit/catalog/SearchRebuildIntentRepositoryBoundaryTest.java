package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.search.ElasticsearchProductSearch;
import com.example.campusmarket.catalog.search.SearchRebuildReconciler;
import com.example.campusmarket.catalog.search.SearchRebuildService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildIntentRepositoryBoundaryTest {
    @Test
    void intentPersistenceHasDedicatedRepositoryAndLeavesEsAdapter() throws Exception {
        Class<?> intentRepository = loadIntentRepository();

        assertThat(Arrays.stream(ElasticsearchProductSearch.class.getDeclaredFields())
            .map(Field::getName)).doesNotContain("cleanupRepository", "jdbc");
        assertThat(Arrays.stream(ElasticsearchProductSearch.class.getDeclaredMethods())
            .map(Method::getName)
            .noneMatch(name -> name.contains("RebuildIntent") || name.contains("cleanupRepository")
                || name.equals("scheduleCleanup") || name.equals("registerRebuildTarget")
                || name.equals("renewRebuildTarget") || name.equals("armCleanup")
                || name.equals("cancelCleanup"))).isTrue();

        assertThat(Arrays.stream(SearchRebuildService.class.getDeclaredFields())
            .anyMatch(field -> field.getType().equals(intentRepository))).isTrue();
        assertThat(Arrays.stream(SearchRebuildReconciler.class.getDeclaredFields())
            .anyMatch(field -> field.getType().equals(intentRepository))).isTrue();
    }

    @Test
    void intentRepositoryExposesConnectionScopedPersistenceOperations() throws Exception {
        Class<?> repository = loadIntentRepository();
        assertThat(repository.getDeclaredMethods()).extracting(Method::getName)
            .contains("record", "markBuilding", "claimSwitch", "markSwitched", "updatePrevious",
                "assertSwitching", "hasLiveSwitching", "latestSuccessful", "latestRecoverableLive",
                "candidates", "claim", "readClaimed", "markReconciled");
    }

    private static Class<?> loadIntentRepository() {
        try {
            return Class.forName("com.example.campusmarket.catalog.search.SearchRebuildIntentRepository");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("search_rebuild_intent 持久化必须由专用仓储承载", missing);
        }
    }
}
