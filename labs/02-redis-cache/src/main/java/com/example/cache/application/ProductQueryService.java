package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.domain.ProductRepository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class ProductQueryService {

    private static final long REBUILD_LOCK_WAIT_MILLIS = 200;

    private final ProductRepository repository;
    private final ProductCache cache;
    private final RebuildLock rebuildLock;

    public ProductQueryService(ProductRepository repository, ProductCache cache) {
        this(repository, cache, new LocalRebuildLock(id -> {
        }));
    }

    public ProductQueryService(ProductRepository repository, ProductCache cache, RebuildLock rebuildLock) {
        this.repository = repository;
        this.cache = cache;
        this.rebuildLock = rebuildLock;
    }

    ProductQueryService(
            ProductRepository repository,
            ProductCache cache,
            RebuildLockObserver rebuildLockObserver
    ) {
        this(repository, cache, new LocalRebuildLock(rebuildLockObserver));
    }

    public ProductView getProduct(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("product id must be positive");
        }

        ProductView cached = cachedProductView(id);
        if (cached != null) {
            return cached;
        }

        Optional<? extends RebuildLock.LockHandle> acquiredLock = rebuildLock.tryAcquire(id);
        if (acquiredLock.isEmpty()) {
            ProductView rebuilt = cachedProductView(id);
            if (rebuilt != null) {
                return rebuilt;
            }
            throw new IllegalStateException("系统繁忙，请稍后重试");
        }

        try {
            ProductView rebuilt = cachedProductView(id);
            if (rebuilt != null) {
                return rebuilt;
            }

            return repository.findById(id)
                    .map(this::cacheAndReturn)
                    .orElseGet(() -> cacheNegativeAndReturnNotFound(id));
        } finally {
            rebuildLock.release(acquiredLock.orElseThrow());
        }
    }

    int activeRebuildLockCount() {
        return rebuildLock instanceof LocalRebuildLock localRebuildLock
                ? localRebuildLock.activeLockCount()
                : 0;
    }

    boolean hasQueuedRebuildLock(long id) {
        return rebuildLock instanceof LocalRebuildLock localRebuildLock
                && localRebuildLock.hasQueuedLock(id);
    }

    private ProductView cachedProductView(long id) {
        CacheLookup cached = cache.get(id);
        if (cached instanceof CacheLookup.ProductHit productHit) {
            return ProductView.found(productHit.product());
        }
        if (cached instanceof CacheLookup.NegativeHit) {
            return ProductView.notFound();
        }
        return null;
    }

    private ProductView cacheAndReturn(Product product) {
        cache.put(product);
        return ProductView.found(product);
    }

    private ProductView cacheNegativeAndReturnNotFound(long id) {
        cache.putNegative(id);
        return ProductView.notFound();
    }

    private static final class LocalRebuildLock implements RebuildLock {
        private final RebuildLockObserver observer;
        private final ConcurrentHashMap<Long, LocalLock> locks = new ConcurrentHashMap<>();

        private LocalRebuildLock(RebuildLockObserver observer) {
            this.observer = observer;
        }

        @Override
        public Optional<LockHandle> tryAcquire(long productId) {
            LocalLock localLock = retain(productId);
            observer.afterRetain(productId);
            try {
                if (localLock.lock.tryLock(REBUILD_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    return Optional.of(new LocalLockHandle(productId, localLock));
                }
                releaseReference(productId, localLock);
                return Optional.empty();
            } catch (InterruptedException exception) {
                releaseReference(productId, localLock);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("系统繁忙，请稍后重试", exception);
            }
        }

        @Override
        public void release(LockHandle lock) {
            if (!(lock instanceof LocalLockHandle localLockHandle)) {
                throw new IllegalArgumentException("lock handle was not created by LocalRebuildLock");
            }
            localLockHandle.lock().lock.unlock();
            releaseReference(localLockHandle.productId(), localLockHandle.lock());
        }

        private int activeLockCount() {
            return locks.size();
        }

        private boolean hasQueuedLock(long productId) {
            LocalLock localLock = locks.get(productId);
            return localLock != null && localLock.lock.hasQueuedThreads();
        }

        private LocalLock retain(long productId) {
            return locks.compute(productId, (ignored, existing) -> {
                LocalLock localLock = existing == null ? new LocalLock() : existing;
                localLock.participants++;
                return localLock;
            });
        }

        private void releaseReference(long productId, LocalLock localLock) {
            locks.computeIfPresent(productId, (ignored, existing) -> {
                if (existing != localLock) {
                    throw new IllegalStateException("rebuild lock lifecycle is inconsistent");
                }
                localLock.participants--;
                return localLock.participants == 0 ? null : localLock;
            });
        }
    }

    private static final class LocalLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int participants;
    }

    private record LocalLockHandle(long productId, LocalLock lock) implements RebuildLock.LockHandle {
    }

    @FunctionalInterface
    interface RebuildLockObserver {
        void afterRetain(long id);
    }
}
