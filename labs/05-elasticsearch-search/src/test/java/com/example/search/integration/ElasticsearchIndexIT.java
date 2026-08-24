package com.example.search.integration;

import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

/** SmartCN, strict mapping and alias contract against the repository image. */
@SpringBootTest
class ElasticsearchIndexIT extends SharedSearchContainers {
    @Autowired ElasticsearchIndexManager manager;
    @Autowired ElasticsearchClient client;
    @Autowired SearchIndexBootstrap bootstrap;

    @Test
    void installsSmartCnAndRejectsUnknownFields() {
        assertThat(manager.pluginNames()).contains("analysis-smartcn");
        String index = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        assertThatThrownBy(() -> client.index(i -> i.index(index).id("raw")
                        .document(java.util.Map.of("productId", 1, "unknown", true))))
                .hasMessageContaining("strict_dynamic_mapping_exception");
    }

    @Test
    void bootstrapsReadAndWriteAliases() {
        var targets = bootstrap.ensureInitialized();
        assertThat(targets.read()).isEqualTo(targets.write());
        assertThat(manager.aliasTargets()).isEqualTo(targets);
    }
}
