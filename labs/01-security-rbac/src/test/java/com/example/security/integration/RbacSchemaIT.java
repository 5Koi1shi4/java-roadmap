package com.example.security.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RbacSchemaIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("security_lab")
            .withUsername("security")
            .withPassword("security");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsRbacAndRefreshTokenTables() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                """,
                String.class
        );

        assertThat(tables).contains(
                "sys_user",
                "sys_role",
                "sys_permission",
                "sys_user_role",
                "sys_role_permission",
                "refresh_token"
        );
    }

    @Test
    void definesBusinessUniqueIndexes() {
        List<String> indexes = jdbc.queryForList(
                """
                SELECT CONCAT(table_name, '.', index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND non_unique = 0
                """,
                String.class
        );

        assertThat(indexes).contains(
                "sys_user.uk_sys_user_username",
                "sys_role.uk_sys_role_code",
                "sys_permission.uk_sys_permission_code",
                "sys_user_role.uk_sys_user_role",
                "sys_role_permission.uk_sys_role_permission",
                "refresh_token.uk_refresh_token_hash"
        );
    }
}
