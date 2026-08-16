package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.domain.ProductRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerCacheMetricsTest {

    @Test
    void recordsPositiveAndNegativeCacheHits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Product product = new Product(7L, "Java 编程思想", 9_900L);
        ProductQueryService service = new ProductQueryService(
                new FixedRepository(Optional.empty()),
                new FixedCache(ProductCache.CacheLookup.product(product)),
                new MicrometerCacheMetrics(registry)
        );

        assertThat(service.getProduct(7L)).isEqualTo(ProductView.found(product));

        ProductQueryService negativeService = new ProductQueryService(
                new FixedRepository(Optional.empty()),
                new FixedCache(ProductCache.CacheLookup.negative()),
                new MicrometerCacheMetrics(registry)
        );
        assertThat(negativeService.getProduct(8L)).isEqualTo(ProductView.notFound());

        assertThat(registry.get("cache.hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.negative_hit").counter().count()).isEqualTo(1);
    }

    @Test
    void recordsMissAndRepositoryLoadForAColdProduct() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Product product = new Product(7L, "Java 编程思想", 9_900L);
        ProductQueryService service = new ProductQueryService(
                new FixedRepository(Optional.of(product)),
                new FixedCache(ProductCache.CacheLookup.miss()),
                new MicrometerCacheMetrics(registry)
        );

        assertThat(service.getProduct(7L)).isEqualTo(ProductView.found(product));

        assertThat(registry.get("cache.miss").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.repository_load").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.lock.wait").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsLockBusyOnlyWhenTheFinalCacheCheckStillMisses() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Product product = new Product(7L, "Java 编程思想", 9_900L);
        ProductQueryService cacheFilledService = new ProductQueryService(
                new FixedRepository(Optional.empty()),
                new SequentialCache(CacheLookup.miss(), CacheLookup.product(product)),
                new BusyRebuildLock(),
                new MicrometerCacheMetrics(registry)
        );

        assertThat(cacheFilledService.getProduct(7L)).isEqualTo(ProductView.found(product));
        assertThat(registry.get("cache.lock_busy").counter().count()).isZero();

        ProductQueryService cacheMissService = new ProductQueryService(
                new FixedRepository(Optional.empty()),
                new FixedCache(ProductCache.CacheLookup.miss()),
                new BusyRebuildLock(),
                new MicrometerCacheMetrics(registry)
        );

        assertThatThrownBy(() -> cacheMissService.getProduct(8L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("系统繁忙，请稍后重试");

        assertThat(registry.get("cache.lock_busy").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.lock.wait").timer().count()).isEqualTo(2);
        assertThat(registry.get("cache.hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.repository_load").counter().count()).isZero();
    }

    @Test
    void recordsHitWhenTheSecondCacheCheckFindsAProduct() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Product product = new Product(7L, "Java 编程思想", 9_900L);
        ProductQueryService service = new ProductQueryService(
                new FixedRepository(Optional.empty()),
                new SequentialCache(CacheLookup.miss(), CacheLookup.product(product)),
                new MicrometerCacheMetrics(registry)
        );

        assertThat(service.getProduct(7L)).isEqualTo(ProductView.found(product));

        assertThat(registry.get("cache.miss").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.repository_load").counter().count()).isZero();
    }

    @Test
    void recordsNegativeHitWhenTheSecondCacheCheckFindsANegativeEntry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProductQueryService service = new ProductQueryService(
                new FixedRepository(Optional.empty()),
                new SequentialCache(CacheLookup.miss(), CacheLookup.negative()),
                new MicrometerCacheMetrics(registry)
        );

        assertThat(service.getProduct(8L)).isEqualTo(ProductView.notFound());

        assertThat(registry.get("cache.miss").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.negative_hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("cache.repository_load").counter().count()).isZero();
    }

    private static final class FixedCache implements ProductCache {
        private final CacheLookup lookup;

        private FixedCache(CacheLookup lookup) {
            this.lookup = lookup;
        }

        @Override
        public CacheLookup get(long id) {
            return lookup;
        }

        @Override
        public void put(Product product) {
        }

        @Override
        public void putNegative(long id) {
        }

        @Override
        public void evict(long id) {
        }
    }

    private static final class SequentialCache implements ProductCache {
        private final Deque<CacheLookup> lookups;

        private SequentialCache(CacheLookup... lookups) {
            this.lookups = new ArrayDeque<>(java.util.List.of(lookups));
        }

        @Override
        public CacheLookup get(long id) {
            return lookups.removeFirst();
        }

        @Override
        public void put(Product product) {
        }

        @Override
        public void putNegative(long id) {
        }

        @Override
        public void evict(long id) {
        }
    }

    private record FixedRepository(Optional<Product> product) implements ProductRepository {
        @Override
        public Optional<Product> findById(long id) {
            return product;
        }

        @Override
        public void update(Product product) {
            throw new UnsupportedOperationException("not needed by metrics test");
        }
    }

    private static final class BusyRebuildLock implements RebuildLock {
        @Override
        public Optional<LockHandle> tryAcquire(long productId) {
            return Optional.empty();
        }

        @Override
        public void release(LockHandle lock) {
            throw new UnsupportedOperationException("lock was never acquired");
        }
    }
}
