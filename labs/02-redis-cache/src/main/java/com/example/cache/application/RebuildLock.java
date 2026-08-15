package com.example.cache.application;

import java.util.Optional;

/**
 * 为缓存未命中后的回源操作提供互斥保护。
 */
public interface RebuildLock {

    Optional<? extends LockHandle> tryAcquire(long productId);

    void release(LockHandle lock);

    interface LockHandle {
    }
}
