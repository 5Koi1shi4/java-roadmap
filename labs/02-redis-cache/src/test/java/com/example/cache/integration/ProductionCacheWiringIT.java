package com.example.cache.integration;

import com.example.cache.CacheApplication;
import com.example.cache.application.ProductQueryService;
import com.example.cache.application.RebuildLock;
import com.example.cache.infrastructure.RedissonRebuildLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = CacheApplication.class)
class ProductionCacheWiringIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private RebuildLock rebuildLock;

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
}
