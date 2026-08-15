package com.example.cache.infrastructure;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RedisRebuildLock {

    private static final String KEY_PREFIX = "lock:product:rebuild:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRebuildLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
    }

    public Optional<LockHandle> tryAcquire(long productId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(productId), token, LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.of(new LockHandle(productId, token));
        }
        return Optional.empty();
    }

    public void release(LockHandle lock) {
        Objects.requireNonNull(lock);
        redisTemplate.execute(COMPARE_AND_DELETE, List.of(key(lock.productId())), lock.token());
    }

    private String key(long productId) {
        return KEY_PREFIX + productId;
    }

    public record LockHandle(long productId, String token) {
    }
}
