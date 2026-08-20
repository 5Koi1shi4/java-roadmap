package com.example.cache.integration;

import com.example.cache.application.ProductQueryService;
import com.example.cache.application.ProductView;
import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import com.example.cache.infrastructure.RedisProductCache;
import com.example.cache.infrastructure.RedisRebuildLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProductQueryServiceRedisRebuildLockIT {

    private static final int CONCURRENT_REQUESTS = 100;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private CountingProductRepository repository;
    private ProductQueryService firstService;
    private ProductQueryService secondService;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        Product product = new Product(7L, "Java 编程思想", 99_00L);
        repository = new CountingProductRepository(product);
        ProductCache sharedCache = new FirstReadBarrierCache(
                new RedisProductCache(redisTemplate, new ObjectMapper()), CONCURRENT_REQUESTS);
        firstService = new ProductQueryService(repository, sharedCache, new RedisRebuildLock(redisTemplate));
        secondService = new ProductQueryService(repository, sharedCache, new RedisRebuildLock(redisTemplate));
    }

    @Test
    void twoServiceInstancesRebuildOneExpiredHotKeyOnlyOnceAcrossOneHundredConcurrentRequests() throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Object> outcomes = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            var calls = java.util.stream.IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        try {
                            if (!start.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test start timed out");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted before test start", exception);
                        }
                        try {
                            outcomes.add((index & 1) == 0
                                    ? firstService.getProduct(7L)
                                    : secondService.getProduct(7L));
                        } catch (IllegalStateException exception) {
                            outcomes.add(exception.getMessage());
                        }
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.findCalls.get()).isEqualTo(1);
        assertThat(outcomes).hasSize(CONCURRENT_REQUESTS)
                .allSatisfy(outcome -> assertThat(outcome)
                        .isIn(ProductView.found(repository.product), "系统繁忙，请稍后重试"));
    }

    @Test
    void waitsForTheLockOwnerToPopulateTheCacheBeforeReturningToACompetingInstance() throws Exception {
        Product product = new Product(7L, "Java 编程思想", 99_00L);
        DelayedProductRepository delayedRepository = new DelayedProductRepository(product);
        ProductCache sharedCache = new RedisProductCache(redisTemplate, new ObjectMapper());
        ProductQueryService lockOwner = new ProductQueryService(
                delayedRepository, sharedCache, new RedisRebuildLock(redisTemplate));
        ProductQueryService competitor = new ProductQueryService(
                delayedRepository, sharedCache, new RedisRebuildLock(redisTemplate));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var ownerCall = executor.submit(() -> lockOwner.getProduct(7L));
            assertThat(delayedRepository.lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            ProductView competitorResult = competitor.getProduct(7L);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(competitorResult).isEqualTo(ProductView.found(product));
            assertThat(elapsedMillis).isLessThan(500L);
            assertThat(ownerCall.get(5, TimeUnit.SECONDS)).isEqualTo(ProductView.found(product));
        } finally {
            executor.shutdownNow();
        }

        assertThat(delayedRepository.findCalls.get()).isEqualTo(1);
    }

    @Test
    void preservesTheInterruptedStatusWhenLockAcquisitionIsWaiting() {
        RedisRebuildLock firstClient = new RedisRebuildLock(redisTemplate);
        RedisRebuildLock interruptedClient = new RedisRebuildLock(redisTemplate);
        var ownerLock = firstClient.tryAcquire(7L).orElseThrow();
        try {
            Thread.currentThread().interrupt();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> interruptedClient.tryAcquire(7L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted while waiting for Redis rebuild lock");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            firstClient.release(ownerLock);
        }
    }

    private static final class CountingProductRepository implements ProductRepository {
        private final Product product;
        private final AtomicInteger findCalls = new AtomicInteger();

        private CountingProductRepository(Product product) {
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

    private static final class DelayedProductRepository implements ProductRepository {
        private final Product product;
        private final AtomicInteger findCalls = new AtomicInteger();
        private final CountDownLatch lookupStarted = new CountDownLatch(1);

        private DelayedProductRepository(Product product) {
            this.product = product;
        }

        @Override
        public Optional<Product> findById(long id) {
            findCalls.incrementAndGet();
            lookupStarted.countDown();
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while simulating repository lookup", exception);
            }
            return Optional.of(product);
        }

        @Override
        public void update(Product product) {
            throw new UnsupportedOperationException("not needed by query test");
        }
    }

    private static final class FirstReadBarrierCache implements ProductCache {
        private final ProductCache delegate;
        private final CountDownLatch firstReads;

        private FirstReadBarrierCache(ProductCache delegate, int concurrentRequests) {
            this.delegate = delegate;
            this.firstReads = new CountDownLatch(concurrentRequests);
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
                    throw new IllegalStateException("interrupted while waiting for test cache barrier", exception);
                }
                return CacheLookup.miss();
            }
            return delegate.get(id);
        }

        @Override
        public void put(Product product) {
            delegate.put(product);
        }

        @Override
        public void putNegative(long id) {
            delegate.putNegative(id);
        }

        @Override
        public void evict(long id) {
            delegate.evict(id);
        }
    }
}
