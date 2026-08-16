package com.example.cache.integration;

import com.example.cache.CacheApplication;
import com.example.cache.application.ProductQueryService;
import com.example.cache.application.ProductUpdateService;
import com.example.cache.application.RebuildLock;
import com.example.cache.application.UpdateProductCommand;
import com.example.cache.infrastructure.RedissonRebuildLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = CacheApplication.class)
class ProductionCacheWiringIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private RebuildLock rebuildLock;

    @Autowired
    private ProductUpdateService productUpdateService;

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

    @Test
    void wiresTheRedissonRebuildLockBeanIntoProductQueryService() {
        assertThat(rebuildLock).isInstanceOf(RedissonRebuildLock.class);
        assertThat(ReflectionTestUtils.getField(productQueryService, "rebuildLock")).isSameAs(rebuildLock);
    }

    @Test
    void productionUpdateBeanEvictsThePrefilledCacheEntryAfterCommit() {
        createProductTable();
        redisTemplate.opsForValue().set("product:v1:7", "{\"id\":7,\"name\":\"Java 编程思想\",\"priceInCents\":9900}");

        productUpdateService.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM products WHERE id = ?", String.class, 7L)).isEqualTo("Effective Java");
        assertThat(redisTemplate.hasKey("product:v1:7")).isFalse();
    }

    @Test
    void failedUpdateInsideTheProductionTransactionRetainsDatabaseAndCache() {
        createProductTable();
        redisTemplate.opsForValue().set("product:v1:7", "{\"id\":7,\"name\":\"Java 编程思想\",\"priceInCents\":9900}");
        jdbcTemplate.execute("CREATE TRIGGER reject_product_updates BEFORE UPDATE ON products "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'update rejected'");

        assertThatThrownBy(() -> productUpdateService.updateProduct(
                new UpdateProductCommand(7L, "Effective Java", 88_00L)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM products WHERE id = ?", String.class, 7L)).isEqualTo("Java 编程思想");
        assertThat(redisTemplate.hasKey("product:v1:7")).isTrue();
    }

    private void createProductTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS products");
        jdbcTemplate.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, price_in_cents BIGINT NOT NULL)");
        jdbcTemplate.update("INSERT INTO products (id, name, price_in_cents) VALUES (?, ?, ?)",
                7L, "Java 编程思想", 99_00L);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}
