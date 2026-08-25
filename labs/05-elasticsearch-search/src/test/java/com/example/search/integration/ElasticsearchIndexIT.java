package com.example.search.integration;

import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.maintenance.SplitAliasException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

/** SmartCN, strict mapping and alias contract against the repository image. */
@SpringBootTest
class ElasticsearchIndexIT extends SharedSearchContainers {
    @Autowired ElasticsearchIndexManager manager;
    @Autowired ElasticsearchClient client;
    @Autowired SearchIndexBootstrap bootstrap;
    private final List<String> indexesCreatedByTest = new ArrayList<>();

    @AfterEach
    void cleanAliasesAndIndexesCreatedByThisTest() throws Exception {
        for (String index : indexesCreatedByTest) {
            for (String alias : List.of("products-read", "products-write")) {
                try {
                    client.indices().deleteAlias(d -> d.index(index).name(alias));
                } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ignored) {
                    // Alias may have been removed atomically by the test itself.
                }
            }
            try {
                client.indices().delete(d -> d.index(index));
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ignored) {
                // Index may already be gone.
            }
        }
        indexesCreatedByTest.clear();
    }

    @Test
    void installsSmartCnAndRejectsUnknownFields() throws Exception {
        assertThat(manager.pluginNames()).contains("analysis-smartcn");
        String index = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        indexesCreatedByTest.add(index);
        var analysis = client.indices().analyze(a -> a.index(index).analyzer("smartcn").text("并发编程实战"));
        assertThat(analysis.tokens()).extracting(token -> token.token())
                .contains("并发", "编程")
                .doesNotContain("并", "发", "编", "程");
        assertThatThrownBy(() -> client.index(i -> i.index(index).id("raw")
                        .document(java.util.Map.of("productId", 1, "unknown", true))))
                .hasMessageContaining("strict_dynamic_mapping_exception");
    }

    @Test
    void bootstrapsReadAndWriteAliases() {
        var targets = bootstrap.ensureInitialized();
        indexesCreatedByTest.add(targets.read());
        assertThat(targets.read()).isEqualTo(targets.write());
        assertThat(manager.aliasTargets()).isEqualTo(targets);
    }

    @Test
    void rejectsOnlyOneAliasWithoutRepairingIt() throws Exception {
        String readIndex = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        String writeIndex = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        indexesCreatedByTest.add(readIndex);
        indexesCreatedByTest.add(writeIndex);
        client.indices().updateAliases(u -> u.actions(a -> a.add(x -> x.index(readIndex).alias("products-read"))));

        assertThatThrownBy(bootstrap::ensureInitialized).isInstanceOf(RuntimeException.class);
        assertThat(client.indices().getAlias(g -> g.name("products-read")).result()).containsKey(readIndex);
        assertThatThrownBy(() -> client.indices().getAlias(g -> g.name("products-write")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsDifferentAliasTargetsWithoutRebinding() throws Exception {
        String readIndex = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        String writeIndex = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        indexesCreatedByTest.add(readIndex);
        indexesCreatedByTest.add(writeIndex);
        client.indices().updateAliases(u -> u.actions(
                Action.of(a -> a.add(x -> x.index(readIndex).alias("products-read"))),
                Action.of(a -> a.add(x -> x.index(writeIndex).alias("products-write")))));

        assertThatThrownBy(bootstrap::ensureInitialized).isInstanceOf(IllegalStateException.class);
        assertThat(manager.aliasTargets().read()).isEqualTo(readIndex);
        assertThat(manager.aliasTargets().write()).isEqualTo(writeIndex);
    }

    @Test
    void rejectsAliasPointingToMultiplePhysicalIndexesWithoutRepairingIt() throws Exception {
        String first = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        String second = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        indexesCreatedByTest.add(first);
        indexesCreatedByTest.add(second);
        client.indices().updateAliases(u -> u.actions(
                Action.of(a -> a.add(x -> x.index(first).alias("products-read"))),
                Action.of(a -> a.add(x -> x.index(second).alias("products-read"))),
                Action.of(a -> a.add(x -> x.index(first).alias("products-write")))));

        assertThatThrownBy(bootstrap::ensureInitialized).isInstanceOf(SplitAliasException.class);
        assertThat(client.indices().getAlias(g -> g.name("products-read")).result()).containsKeys(first, second);
    }
}
