package com.example.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.application.sync.DispatchSummary;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the committed MySQL outbox reaches the real Elasticsearch alias. */
@SpringBootTest
class ReliableIndexSyncIT extends SharedSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductCommandService products;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired ElasticsearchIndexManager indexes;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        bootstrap.ensureInitialized();
    }

    @Test
    void synchronizesCreateUpdatesAndLogicalDeleteWithMonotonicVersions() throws Exception {
        ProductView created = products.create(new CreateProductCommand(details("初始名称")));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 1, 0, 0, 0));

        ProductView updated = products.update(created.id(), new UpdateProductCommand(1L, details("更新名称")));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 1, 0, 0, 0));

        products.delete(updated.id(), 2L);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 1, 0, 0, 0));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(0, 0, 0, 0, 0));

        String writeIndex = indexes.aliasTargets().write();
        indexes.refresh(writeIndex);
        Map<String, Object> source = client.get(g -> g.index("products-read").id(Long.toString(created.id())),
                Map.class).source();
        assertThat(source.get("sourceVersion")).isInstanceOf(Number.class);
        assertThat(((Number) source.get("sourceVersion")).longValue()).isEqualTo(3L);
        assertThat(source).containsEntry("status", "DELETED");
    }

    private static ProductDetails details(String name) {
        return new ProductDetails(name, null, "描述", "BOOK", "图书", new BigDecimal("10.00"),
                ProductStatus.ON_SALE);
    }
}
