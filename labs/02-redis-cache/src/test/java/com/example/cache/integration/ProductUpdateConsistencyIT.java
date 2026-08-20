package com.example.cache.integration;

import com.example.cache.application.ProductUpdateService;
import com.example.cache.application.UpdateProductCommand;
import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.infrastructure.JdbcProductRepository;
import com.example.cache.infrastructure.RedisProductCache;
import com.example.cache.infrastructure.SpringTransactionCallbacks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ProductUpdateConsistencyIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactions;
    private static LettuceConnectionFactory connectionFactory;
    private RedisProductCache cache;
    private JdbcProductRepository repository;
    private ProductUpdateService service;

    @BeforeAll
    static void connectToInfrastructure() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        dataSource.setDriverClassName(MYSQL.getDriverClassName());
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
    }

    @AfterAll
    static void disconnectFromRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS products");
        jdbcTemplate.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, price_in_cents BIGINT NOT NULL)");
        jdbcTemplate.update("INSERT INTO products (id, name, price_in_cents) VALUES (?, ?, ?)", 7L, "Java 编程思想", 99_00L);

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        cache = new RedisProductCache(redisTemplate, new ObjectMapper());
        repository = new JdbcProductRepository(jdbcTemplate);
        service = new ProductUpdateService(repository, cache, new SpringTransactionCallbacks());
    }

    @Test
    void committedUpdateDeletesTheCachedProduct() {
        Product oldProduct = new Product(7L, "Java 编程思想", 99_00L);
        cache.put(oldProduct);

        transactions.executeWithoutResult(status -> service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L)));

        assertThat(repository.findById(7L)).contains(new Product(7L, "Effective Java", 88_00L));
        assertThat(cache.get(7L)).isEqualTo(CacheLookup.miss());
    }

    @Test
    void rolledBackUpdatePreservesTheDatabaseAndCachedProduct() {
        Product oldProduct = new Product(7L, "Java 编程思想", 99_00L);
        cache.put(oldProduct);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

        assertThat(repository.findById(7L)).contains(oldProduct);
        assertThat(cache.get(7L)).isEqualTo(CacheLookup.product(oldProduct));
    }

    @Test
    void evictRemovesBothPositiveAndNegativeEntries() {
        cache.put(new Product(7L, "Java 编程思想", 99_00L));
        cache.evict(7L);
        assertThat(cache.get(7L)).isEqualTo(CacheLookup.miss());

        cache.putNegative(8L);
        cache.evict(8L);
        assertThat(cache.get(8L)).isEqualTo(CacheLookup.miss());
    }
}
