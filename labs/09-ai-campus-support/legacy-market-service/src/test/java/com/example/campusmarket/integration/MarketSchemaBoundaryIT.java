package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** market_db 只拥有交易领域表，不跨库依赖 identity_db。 */
@SpringBootTest(classes = LegacyMarketApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MarketSchemaBoundaryIT extends Task11MySqlContainers {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void marketSchemaContainsNoIdentityTablesOrCrossSchemaForeignKeys() {
        assertThat(tableNames()).doesNotContain("campus_user", "email_verification", "external_identity");
        assertThat(importedKeyTargets()).noneMatch(name -> name.startsWith("identity_db."));
    }

    private Set<String> tableNames() {
        return new HashSet<>(jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
            String.class));
    }

    private Set<String> importedKeyTargets() {
        return new HashSet<>(jdbc.queryForList(
            "SELECT DISTINCT CONCAT(REFERENCED_TABLE_SCHEMA, '.', REFERENCED_TABLE_NAME) "
                + "FROM information_schema.KEY_COLUMN_USAGE "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL",
            String.class));
    }
}
