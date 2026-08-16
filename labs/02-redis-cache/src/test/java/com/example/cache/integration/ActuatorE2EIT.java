package com.example.cache.integration;

import com.example.cache.CacheApplication;
import com.example.cache.domain.Product;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = CacheApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorE2EIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void prepareProductData() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        jdbcTemplate.execute("DROP TABLE IF EXISTS products");
        jdbcTemplate.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, price_in_cents BIGINT NOT NULL)");
        jdbcTemplate.update("INSERT INTO products (id, name, price_in_cents) VALUES (?, ?, ?)", 7L, "Java 编程思想", 9_900L);
    }

    @Test
    void exposesPositiveAndNegativeCacheHitsAfterRepeatedHttpRequests() {
        assertThat(rest.getForEntity("/api/products/7", Product.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/products/7", Product.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/products/8", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/products/8", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(metricCount("cache.hit")).isEqualTo(1.0);
        assertThat(metricCount("cache.negative_hit")).isEqualTo(1.0);
    }

    private double metricCount(String metricName) {
        JsonNode response = rest.getForObject("/actuator/metrics/{metricName}", JsonNode.class, metricName);
        for (JsonNode measurement : response.path("measurements")) {
            if ("COUNT".equals(measurement.path("statistic").asText())) {
                return measurement.path("value").asDouble();
            }
        }
        throw new AssertionError("missing COUNT measurement for " + metricName);
    }
}
