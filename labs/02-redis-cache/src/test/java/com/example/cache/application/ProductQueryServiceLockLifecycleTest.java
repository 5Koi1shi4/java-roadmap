package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.domain.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQueryServiceLockLifecycleTest {

    @Test
    void retainsOneLockWhileQueuedRequestsArriveDuringARebuild() throws Exception {
        long productId = 7L;
        Product product = new Product(productId, "Java 编程思想", 99_00L);
        BlockingProductRepository repository = new BlockingProductRepository(product);
        CountDownLatch secondRequestRetained = new CountDownLatch(1);
        CountDownLatch thirdRequestRetained = new CountDownLatch(1);
        AtomicInteger retainCalls = new AtomicInteger();
        ProductQueryService service = new ProductQueryService(
                repository,
                new ConcurrentProductCache(),
                id -> {
                    int call = retainCalls.incrementAndGet();
                    if (call == 2) {
                        secondRequestRetained.countDown();
                    }
                    if (call == 3) {
                        thirdRequestRetained.countDown();
                    }
                }
        );
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            var rebuildingRequest = executor.submit(() -> service.getProduct(productId));
            assertThat(repository.lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var waitingRequest = executor.submit(() -> service.getProduct(productId));
            assertThat(secondRequestRetained.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(await(() -> service.hasQueuedRebuildLock(productId))).isTrue();

            var followingRequest = executor.submit(() -> service.getProduct(productId));
            assertThat(thirdRequestRetained.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.activeRebuildLockCount()).isEqualTo(1);
            assertThat(await(() -> service.hasQueuedRebuildLock(productId))).isTrue();

            repository.releaseLookup.countDown();

            assertThat(rebuildingRequest.get(5, TimeUnit.SECONDS)).isEqualTo(ProductView.found(product));
            assertThat(waitingRequest.get(5, TimeUnit.SECONDS)).isEqualTo(ProductView.found(product));
            assertThat(followingRequest.get(5, TimeUnit.SECONDS)).isEqualTo(ProductView.found(product));
        } finally {
            repository.releaseLookup.countDown();
            executor.shutdownNow();
        }

        assertThat(repository.findCalls.get()).isEqualTo(1);
        assertThat(service.activeRebuildLockCount()).isZero();
    }

    private boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class BlockingProductRepository implements ProductRepository {
        private final Product product;
        private final AtomicInteger findCalls = new AtomicInteger();
        private final CountDownLatch lookupStarted = new CountDownLatch(1);
        private final CountDownLatch releaseLookup = new CountDownLatch(1);

        private BlockingProductRepository(Product product) {
            this.product = product;
        }

        @Override
        public Optional<Product> findById(long id) {
            findCalls.incrementAndGet();
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

    private static final class ConcurrentProductCache implements ProductCache {
        private final Map<Long, CacheLookup> entries = new ConcurrentHashMap<>();

        @Override
        public CacheLookup get(long id) {
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
