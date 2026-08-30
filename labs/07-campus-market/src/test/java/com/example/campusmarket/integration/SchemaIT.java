package com.example.campusmarket.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIT extends SharedContainers {
    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .load()
            .migrate();
    }

    @Test
    void createsAllCoreTables() throws SQLException {
        Set<String> expected = Set.of(
            "campus_user", "user_login_identity", "campus_role", "user_role",
            "listing", "listing_image", "listing_category", "listing_favorite",
            "trade_order", "trade_order_item", "payment_order", "refund_order", "payment_attempt",
            "dispute_case", "dispute_evidence", "review", "warranty_case", "seller_obligation",
            "integration_outbox", "consumed_event", "object_upload_session", "storage_cleanup_task",
            "conversation", "conversation_member", "message", "notification", "audit_log");
        assertThat(tableNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).hasSize(27);
    }

    @Test
    void preservesMoneyQuantityAndIdempotencyConstraints() throws SQLException {
        assertThat(columnType("trade_order", "total_amount_fen")).isEqualTo("bigint");
        assertThat(indexNames("refund_order")).contains("uk_refund_idempotency", "idx_refund_reconcile");
        assertThat(indexNames("integration_outbox")).contains("uk_outbox_provider_event");
        assertThat(indexNames("consumed_event")).contains("uk_consumed_event");
    }

    private Set<String> tableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                String name = result.getString("TABLE_NAME");
                if (!"flyway_schema_history".equals(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            assertThat(result.next()).as("column %s.%s exists", table, column).isTrue();
            return result.getString("TYPE_NAME").toLowerCase();
        }
    }

    private Set<String> indexNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
