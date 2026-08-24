package com.example.search.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Shared real MySQL service for integration tests in this experiment. */
@Testcontainers
public abstract class SharedMySqlContainer {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("product_search")
            .withUsername("product_search")
            .withPassword("product_search");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    protected static void clearTables(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM search_outbox");
            statement.executeUpdate("DELETE FROM search_rebuild_job");
            statement.executeUpdate("DELETE FROM product");
            statement.executeUpdate("UPDATE search_coordination SET dispatcher_paused = FALSE, active_rebuild_id = NULL WHERE id = 1");
        }
    }
}
