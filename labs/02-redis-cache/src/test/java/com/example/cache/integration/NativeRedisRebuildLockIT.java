package com.example.cache.integration;

import com.example.cache.infrastructure.RedisRebuildLock;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class NativeRedisRebuildLockIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisRebuildLock firstClient;
    private RedisRebuildLock secondClient;

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
        firstClient = new RedisRebuildLock(redisTemplate);
        secondClient = new RedisRebuildLock(redisTemplate);
    }

    @Test
    void acquiresLockWithUuidTokenAndThreeSecondExpiry() {
        var lock = firstClient.tryAcquire(42L);

        assertThat(lock).isPresent();
        assertThat(redisTemplate.opsForValue().get("lock:product:rebuild:42"))
                .satisfies(token -> UUID.fromString(token));
        assertThat(redisTemplate.getExpire("lock:product:rebuild:42", TimeUnit.MILLISECONDS))
                .isBetween(1L, 3_000L);
    }

    @Test
    void preventsAnotherClientFromAcquiringBeforeTheLocksTtlExpires() {
        var firstLock = firstClient.tryAcquire(46L).orElseThrow();

        assertThat(secondClient.tryAcquire(46L)).isEmpty();
        assertThat(redisTemplate.opsForValue().get("lock:product:rebuild:46"))
                .isEqualTo(firstLock.token());
    }

    @Test
    void letsAnotherClientAcquireAfterTheLockExpires() throws InterruptedException {
        assertThat(firstClient.tryAcquire(43L)).isPresent();

        Thread.sleep(3_100L);

        assertThat(secondClient.tryAcquire(43L)).isPresent();
    }

    @Test
    void doesNotDeleteANewOwnersLockWhenReleasingAnExpiredToken() throws InterruptedException {
        var expiredLock = firstClient.tryAcquire(44L).orElseThrow();
        Thread.sleep(3_100L);
        var currentLock = secondClient.tryAcquire(44L).orElseThrow();

        firstClient.release(expiredLock);

        assertThat(redisTemplate.opsForValue().get("lock:product:rebuild:44"))
                .isEqualTo(currentLock.token());
    }

    @Test
    void releasesTheCurrentOwnersLock() {
        var lock = firstClient.tryAcquire(45L).orElseThrow();

        firstClient.release(lock);

        assertThat(secondClient.tryAcquire(45L)).isPresent();
    }
}
