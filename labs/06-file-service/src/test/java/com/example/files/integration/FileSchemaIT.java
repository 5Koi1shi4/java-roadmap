package com.example.files.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class FileSchemaIT extends SharedMySqlContainer {

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    @Test
    void createsFileSecurityTablesAndClaimIndexes() throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            assertThat(tableCount(statement, "upload_session")).isOne();
            assertThat(tableCount(statement, "stored_blob")).isOne();
            assertThat(tableCount(statement, "stored_file")).isOne();
            assertThat(tableCount(statement, "file_grant")).isOne();
            assertThat(tableCount(statement, "storage_cleanup_task")).isOne();
            assertThat(tableCount(statement, "file_audit_event")).isOne();
            assertThat(indexCount(statement, "stored_blob", "uk_blob_content_hash")).isOne();
            assertThat(indexCount(statement, "storage_cleanup_task", "idx_cleanup_claim")).isOne();
        }
    }

    private static int tableCount(Statement statement, String tableName) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = '" + tableName + "'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int indexCount(Statement statement, String tableName, String indexName) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + tableName
                + "' AND index_name = '" + indexName + "'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
