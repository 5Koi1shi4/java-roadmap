package com.example.files.integration;

import com.example.files.config.FileServiceProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.util.List;
import org.springframework.util.unit.DataSize;

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

    /** 集成测试显式注入短租约配置，避免生产默认值污染测试时序。 */
    protected static FileServiceProperties testProperties() {
        return new FileServiceProperties(DataSize.ofMegabytes(20), Duration.ofMinutes(10), Duration.ofSeconds(10),
            Duration.ofSeconds(5), Duration.ofMillis(10),
            new FileServiceProperties.Cleanup(50, Duration.ofSeconds(5), List.of(Duration.ofMillis(50)), 3,
                Duration.ofMinutes(5)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(false),
            new FileServiceProperties.Storage("local", "./data/files", "http://localhost:9000", "", "", "secure-files"),
            new FileServiceProperties.Maintenance(false));
    }
}
