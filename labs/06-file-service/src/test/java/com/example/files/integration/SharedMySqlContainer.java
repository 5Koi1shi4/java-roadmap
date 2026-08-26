package com.example.files.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/** Shared real MySQL service for integration tests in this experiment. */
public abstract class SharedMySqlContainer {

    protected static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("secure_files")
                .withUsername("secure_files")
                .withPassword("secure_files_local");
        MYSQL.start();
        Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop, "shared-mysql-container-shutdown"));
    }

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
