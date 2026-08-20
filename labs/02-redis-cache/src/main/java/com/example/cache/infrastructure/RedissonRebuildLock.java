package com.example.cache.infrastructure;

import com.example.cache.application.RebuildLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 使用 Redisson 看门狗维护租约的缓存重建分布式锁。
 */
public final class RedissonRebuildLock implements RebuildLock {

    private static final String KEY_PREFIX = "lock:product:rebuild:";
    private static final long ACQUIRE_WAIT_MILLIS = 200L;

    private final RedissonClient redisson;

    public RedissonRebuildLock(RedissonClient redisson) {
        this.redisson = Objects.requireNonNull(redisson);
    }

    @Override
    public Optional<LockHandle> tryAcquire(long productId) {
        RLock lock = redisson.getLock(key(productId));
        try {
            if (!lock.tryLock(ACQUIRE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
            return Optional.of(new LockHandle(lock));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Redisson rebuild lock", exception);
        } catch (RedisException exception) {
            if (exception.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for Redisson rebuild lock", exception);
            }
            throw exception;
        }
    }

    @Override
    public void release(RebuildLock.LockHandle lock) {
        Objects.requireNonNull(lock);
        if (!(lock instanceof LockHandle redissonLock)) {
            throw new IllegalArgumentException("lock handle was not created by RedissonRebuildLock");
        }
        if (redissonLock.lock().isHeldByCurrentThread()) {
            redissonLock.lock().unlock();
        }
    }

    private String key(long productId) {
        return KEY_PREFIX + productId;
    }

    public record LockHandle(RLock lock) implements RebuildLock.LockHandle {
        public LockHandle {
            Objects.requireNonNull(lock);
        }
    }
}
