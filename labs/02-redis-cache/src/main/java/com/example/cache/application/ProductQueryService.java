package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.domain.ProductRepository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class ProductQueryService {

    private static final long REBUILD_LOCK_WAIT_MILLIS = 200;

    private final ProductRepository repository;
    private final ProductCache cache;
    private final ConcurrentHashMap<Long, ReentrantLock> rebuildLocks = new ConcurrentHashMap<>();

    public ProductQueryService(ProductRepository repository, ProductCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public ProductView getProduct(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("product id must be positive");
        }

        ProductView cached = cachedProductView(id);
        if (cached != null) {
            return cached;
        }

        ReentrantLock rebuildLock = rebuildLocks.computeIfAbsent(id, ignored -> new ReentrantLock());
        if (!tryAcquire(rebuildLock)) {
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
            rebuildLock.unlock();
        }
    }

    private boolean tryAcquire(ReentrantLock rebuildLock) {
        try {
            return rebuildLock.tryLock(REBUILD_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("系统繁忙，请稍后重试", exception);
        }
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
}
