package com.example.search.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SearchSchemaIT extends SharedMySqlContainer {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsSearchTablesAndClaimIndexes() {
        assertThat(tableCount("product")).isOne();
        assertThat(tableCount("search_outbox")).isOne();
        assertThat(indexCount("search_outbox", "uk_search_outbox_product_version_type")).isOne();
        assertThat(indexCount("search_outbox", "idx_search_outbox_claim")).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_coordination WHERE id = 1", Integer.class)).isOne();
    }

    private int tableCount(String tableName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
    }

    private int indexCount(String tableName, String indexName) {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class,
                tableName,
                indexName);
    }
}
