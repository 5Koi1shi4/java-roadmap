package com.example.cache.infrastructure;

import com.example.cache.application.RebuildLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RedisRebuildLock implements RebuildLock {

    private static final String KEY_PREFIX = "lock:product:rebuild:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);
    private static final Duration ACQUIRE_WAIT = Duration.ofMillis(200);
    private static final Duration ACQUIRE_RETRY_INTERVAL = Duration.ofMillis(10);
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRebuildLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
    }

    @Override
    public Optional<LockHandle> tryAcquire(long productId) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("interrupted while waiting for Redis rebuild lock");
        }
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + ACQUIRE_WAIT.toNanos();
        while (true) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(productId), token, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return Optional.of(new LockHandle(productId, token));
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            waitBeforeRetry(deadline);
        }
    }

    @Override
    public void release(RebuildLock.LockHandle lock) {
        Objects.requireNonNull(lock);
        if (!(lock instanceof LockHandle redisLock)) {
            throw new IllegalArgumentException("lock handle was not created by RedisRebuildLock");
        }
        redisTemplate.execute(COMPARE_AND_DELETE, List.of(key(redisLock.productId())), redisLock.token());
    }

    private String key(long productId) {
        return KEY_PREFIX + productId;
    }

    private void waitBeforeRetry(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        long sleepMillis = Math.max(1L, Math.min(
                ACQUIRE_RETRY_INTERVAL.toMillis(),
                Duration.ofNanos(remainingNanos).toMillis()));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Redis rebuild lock", exception);
        }
    }

    public record LockHandle(long productId, String token) implements RebuildLock.LockHandle {
    }
}
