package com.example.search.integration;

import com.example.search.application.sync.IndexMutation;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.infrastructure.elasticsearch.ElasticsearchSearchIndexWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ExternalVersionIT extends SharedSearchContainers {
    @Autowired ElasticsearchIndexManager manager;
    @Autowired ElasticsearchSearchIndexWriter writer;

    @Test
    void staleVersionCannotOverwriteOrReviveTombstone() {
        String index = manager.createPhysicalIndex(java.util.UUID.randomUUID());
        assertThat(writer.bulkWrite(index, List.of(IndexMutation.document(9L, 2L,
                "新名称", "ON_SALE"))).get(0).outcome())
                .isEqualTo(IndexWriteResult.Outcome.APPLIED);
        assertThat(writer.bulkWrite(index, List.of(IndexMutation.tombstone(9L, 3L))).get(0).outcome())
                .isEqualTo(IndexWriteResult.Outcome.APPLIED);
        assertThat(writer.bulkWrite(index, List.of(IndexMutation.document(9L, 2L,
                "旧名称", "ON_SALE"))).get(0).outcome())
                .isEqualTo(IndexWriteResult.Outcome.SUPERSEDED);
        assertThat(manager.readStatus(index, 9L)).isEqualTo("DELETED");
    }
}
