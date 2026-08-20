package com.example.cache.integration;

import com.example.cache.infrastructure.RedissonRebuildLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedissonRebuildLockIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static RedissonClient redisson;
    private RedissonRebuildLock firstClient;
    private RedissonRebuildLock secondClient;

    @BeforeAll
    static void connectToRedis() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void disconnectFromRedis() {
        redisson.shutdown();
    }

    @BeforeEach
    void setUp() {
        redisson.getKeys().flushdb();
        firstClient = new RedissonRebuildLock(redisson);
        secondClient = new RedissonRebuildLock(redisson);
    }

    @Test
    void preventsAnotherThreadFromAcquiringTheSameLockWhileItIsHeld() throws Exception {
        var firstLock = firstClient.tryAcquire(42L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var competingLock = executor.submit(() -> secondClient.tryAcquire(42L));

            assertThat(firstLock).isPresent();
            assertThat(competingLock.get()).isEmpty();
        } finally {
            executor.shutdownNow();
            firstLock.ifPresent(firstClient::release);
        }
    }

    @Test
    void releasesTheLockSoAnotherClientCanAcquireIt() {
        var firstLock = firstClient.tryAcquire(43L).orElseThrow();

        firstClient.release(firstLock);

        assertThat(secondClient.tryAcquire(43L)).isPresent();
    }

    @Test
    void doesNotReleaseTheLockWhenTheCallingThreadIsNotTheOwner() throws Exception {
        var ownerLock = firstClient.tryAcquire(45L).orElseThrow();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var competingLock = executor.submit(() -> {
                firstClient.release(ownerLock);
                return secondClient.tryAcquire(45L);
            });

            assertThat(competingLock.get()).isEmpty();
        } finally {
            executor.shutdownNow();
            firstClient.release(ownerLock);
        }
    }

    @Test
    void restoresInterruptedStatusWhenLockAcquisitionIsInterrupted() {
        var ownerLock = firstClient.tryAcquire(44L).orElseThrow();
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> secondClient.tryAcquire(44L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted while waiting for Redisson rebuild lock");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            firstClient.release(ownerLock);
        }
    }
}
