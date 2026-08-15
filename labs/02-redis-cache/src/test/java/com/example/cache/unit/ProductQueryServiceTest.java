package com.example.cache.unit;

import com.example.cache.application.ProductQueryService;
import com.example.cache.application.ProductQueryServiceTestHooks;
import com.example.cache.application.ProductView;
import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.domain.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductQueryServiceTest {

    private final InMemoryProductRepository repository = new InMemoryProductRepository();
    private final InMemoryProductCache cache = new InMemoryProductCache();
    private ProductQueryService service;

    @BeforeEach
    void setUp() {
        repository.clear();
        cache.clear();
        service = new ProductQueryService(repository, cache);
    }

    @Test
    void loadsProductFromRepositoryAndCachesItAfterCacheMiss() {
        Product product = new Product(7L, "Java 编程思想", 99_00L);
        repository.products.put(7L, product);

        ProductView result = service.getProduct(7L);

        assertThat(result).isEqualTo(ProductView.found(product));
        assertThat(repository.findCalls).isEqualTo(1);
        assertThat(cache.get(7L)).isEqualTo(CacheLookup.product(product));
    }

    @Test
    void returnsCachedProductWithoutRepositoryLookup() {
        Product product = new Product(7L, "Java 编程思想", 99_00L);
        cache.put(product);

        ProductView result = service.getProduct(7L);

        assertThat(result).isEqualTo(ProductView.found(product));
        assertThat(repository.findCalls).isZero();
    }

    @Test
    void cachesMissingProductAsNegativeEntry() {
        ProductView result = service.getProduct(8L);

        assertThat(result).isEqualTo(ProductView.notFound());
        assertThat(repository.findCalls).isEqualTo(1);
        assertThat(cache.get(8L)).isEqualTo(CacheLookup.negative());
    }

    @Test
    void returnsNegativeCacheHitWithoutRepositoryLookup() {
        cache.putNegative(8L);

        ProductView result = service.getProduct(8L);

        assertThat(result).isEqualTo(ProductView.notFound());
        assertThat(repository.findCalls).isZero();
    }

    @Test
    void rejectsNonPositiveIdWithoutReadingCacheOrRepository() {
        assertThatThrownBy(() -> service.getProduct(0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(cache.getCalls).isZero();
        assertThat(repository.findCalls).isZero();
    }

    @Test
    void rebuildsAnExpiredHotKeyWithExactlyOneRepositoryLookup() throws Exception {
        Product product = new Product(7L, "Java 编程思想", 99_00L);
        ConcurrentProductRepository concurrentRepository = new ConcurrentProductRepository(product);
        ProductQueryService concurrentService = new ProductQueryService(
                concurrentRepository,
                new FirstReadBarrierCache(100)
        );
        CountDownLatch ready = new CountDownLatch(100);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(100);
        try {
            var calls = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test start timed out");
                        }
                        return concurrentService.getProduct(7L);
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(calls.stream()
                    .map(call -> {
                        try {
                            return call.get(5, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent product lookup failed", exception);
                        }
                    })
                    .toList()).containsOnly(ProductView.found(product));
        } finally {
            executor.shutdownNow();
        }

        assertThat(concurrentRepository.findCalls.get()).isEqualTo(1);
        assertThat(ProductQueryServiceTestHooks.activeRebuildLockCount(concurrentService)).isZero();
    }

    @Test
    void releasesRebuildLocksAfterDistinctCacheMissesFinish() {
        for (long id = 1; id <= 100; id++) {
            Product product = new Product(id, "Java 编程思想", 99_00L);
            repository.products.put(id, product);

            assertThat(service.getProduct(id)).isEqualTo(ProductView.found(product));
        }

        assertThat(ProductQueryServiceTestHooks.activeRebuildLockCount(service)).isZero();
    }

    @Test
    void reportsSystemBusyWhenAnotherThreadDoesNotFinishRebuildingBeforeTheWaitTimeout() throws Exception {
        BlockingProductRepository blockingRepository = new BlockingProductRepository();
        ProductQueryService concurrentService = new ProductQueryService(blockingRepository, new InMemoryProductCache());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var rebuildingCall = executor.submit(() -> concurrentService.getProduct(7L));
            assertThat(blockingRepository.lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var waitingCall = executor.submit(() -> concurrentService.getProduct(7L));

            assertThatThrownBy(() -> waitingCall.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("系统繁忙，请稍后重试");

            blockingRepository.releaseLookup.countDown();
            assertThat(rebuildingCall.get(5, TimeUnit.SECONDS)).isEqualTo(ProductView.found(blockingRepository.product));
        } finally {
            blockingRepository.releaseLookup.countDown();
            executor.shutdownNow();
        }
    }

    private static final class InMemoryProductRepository implements ProductRepository {
        private final Map<Long, Product> products = new HashMap<>();
        private int findCalls;

        @Override
        public Optional<Product> findById(long id) {
            findCalls++;
            return Optional.ofNullable(products.get(id));
        }

        @Override
        public void update(Product product) {
            products.put(product.id(), product);
        }

        void clear() {
            products.clear();
            findCalls = 0;
        }
    }

    private static final class InMemoryProductCache implements ProductCache {
        private final Map<Long, CacheLookup> entries = new HashMap<>();
        private int getCalls;

        @Override
        public CacheLookup get(long id) {
            getCalls++;
            return entries.getOrDefault(id, CacheLookup.miss());
        }

        @Override
        public void put(Product product) {
            entries.put(product.id(), CacheLookup.product(product));
        }

        @Override
        public void putNegative(long id) {
            entries.put(id, CacheLookup.negative());
        }

        @Override
        public void evict(long id) {
            entries.remove(id);
        }

        void clear() {
            entries.clear();
            getCalls = 0;
        }
    }

    private static final class ConcurrentProductRepository implements ProductRepository {
        private final Product product;
        private final AtomicInteger findCalls = new AtomicInteger();

        private ConcurrentProductRepository(Product product) {
            this.product = product;
        }

        @Override
        public Optional<Product> findById(long id) {
            findCalls.incrementAndGet();
            return Optional.of(product);
        }

        @Override
        public void update(Product product) {
            throw new UnsupportedOperationException("not needed by query test");
        }
    }

    private static final class BlockingProductRepository implements ProductRepository {
        private final Product product = new Product(7L, "Java 编程思想", 99_00L);
        private final CountDownLatch lookupStarted = new CountDownLatch(1);
        private final CountDownLatch releaseLookup = new CountDownLatch(1);

        @Override
        public Optional<Product> findById(long id) {
            lookupStarted.countDown();
            try {
                if (!releaseLookup.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test repository release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for test repository release", exception);
            }
            return Optional.of(product);
        }

        @Override
        public void update(Product product) {
            throw new UnsupportedOperationException("not needed by query test");
        }
    }

    private static final class FirstReadBarrierCache implements ProductCache {
        private final Map<Long, CacheLookup> entries = new ConcurrentHashMap<>();
        private final CountDownLatch firstReads;

        private FirstReadBarrierCache(int concurrentCallCount) {
            firstReads = new CountDownLatch(concurrentCallCount);
        }

        @Override
        public CacheLookup get(long id) {
            if (firstReads.getCount() > 0) {
                firstReads.countDown();
                try {
                    if (!firstReads.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent first cache reads timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for first cache reads", exception);
                }
                return CacheLookup.miss();
            }
            return entries.getOrDefault(id, CacheLookup.miss());
        }

        @Override
        public void put(Product product) {
            entries.put(product.id(), CacheLookup.product(product));
        }

        @Override
        public void putNegative(long id) {
            entries.put(id, CacheLookup.negative());
        }

        @Override
        public void evict(long id) {
            entries.remove(id);
        }
    }

}
