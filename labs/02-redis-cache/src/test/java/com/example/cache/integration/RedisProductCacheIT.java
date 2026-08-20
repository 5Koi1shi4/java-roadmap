package com.example.cache.integration;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.infrastructure.RedisProductCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisProductCacheIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisProductCache cache;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        cache = new RedisProductCache(redisTemplate, new ObjectMapper());
    }

    @Test
    void storesAndReadsProductWithTenMinuteExpiry() {
        Product product = new Product(12L, "Redis 实战", 88_00L);

        cache.put(product);

        assertThat(cache.get(12L)).isEqualTo(CacheLookup.product(product));
        assertThat(redisTemplate.getExpire("product:v1:12", TimeUnit.SECONDS)).isBetween(1L, 600L);
    }

    @Test
    void storesNegativeEntryWithThirtySecondExpiry() {
        cache.putNegative(99L);

        assertThat(cache.get(99L)).isEqualTo(CacheLookup.negative());
        assertThat(redisTemplate.getExpire("product:v1:99", TimeUnit.SECONDS)).isBetween(1L, 30L);
    }
}
