package com.example.cache.integration;

import com.example.cache.application.ProductQueryService;
import com.example.cache.application.ProductView;
import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import com.example.cache.infrastructure.RedisProductCache;
import com.example.cache.infrastructure.RedissonRebuildLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProductQueryServiceRedissonRebuildLockIT {

    private static final int CONCURRENT_REQUESTS = 100;
    private static final long COMPETING_REQUEST_MAX_MILLIS = 2_000L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedissonClient firstRedissonClient;
    private static RedissonClient secondRedissonClient;

    private DelayedCountingProductRepository repository;
    private Product product;
    private ProductQueryService firstService;
    private ProductQueryService secondService;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        firstRedissonClient = newRedissonClient();
        secondRedissonClient = newRedissonClient();
    }

    @AfterAll
    static void disconnectFromRedis() {
        firstRedissonClient.shutdown();
        secondRedissonClient.shutdown();
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        firstRedissonClient.getKeys().flushdb();
        product = new Product(7L, "Java 编程思想", 99_00L);
        repository = new DelayedCountingProductRepository(product);
        ProductCache sharedCache = new FirstReadBarrierCache(
                new RedisProductCache(redisTemplate, new ObjectMapper()), CONCURRENT_REQUESTS);
        firstService = new ProductQueryService(repository, sharedCache, new RedissonRebuildLock(firstRedissonClient));
        secondService = new ProductQueryService(repository, sharedCache, new RedissonRebuildLock(secondRedissonClient));
    }

    @Test
    void twoIndependentRedissonClientsRebuildOneExpiredHotKeyOnlyOnceForOneHundredConcurrentRequests()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<java.util.concurrent.Future<RequestOutcome>> calls = new ArrayList<>();
            for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
                ProductQueryService service = (index & 1) == 0 ? firstService : secondService;
                calls.add(executor.submit(() -> invokeAfterStart(service, ready, start)));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RequestOutcome> outcomes = new ArrayList<>();
            for (var call : calls) {
                outcomes.add(call.get(10, TimeUnit.SECONDS));
            }

            assertThat(repository.findCalls.get()).isEqualTo(1);
            assertThat(outcomes)
                    .allSatisfy(outcome -> assertThat(outcome.result())
                            .isIn(ProductView.found(product), "系统繁忙，请稍后重试"));
            assertThat(outcomes)
                    .allSatisfy(outcome -> assertThat(outcome.elapsedMillis())
                            .as("竞争请求应在锁等待窗口外保留调度余量后完成")
                            .isLessThan(COMPETING_REQUEST_MAX_MILLIS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static RequestOutcome invokeAfterStart(
            ProductQueryService service,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before test start", exception);
        }

        long startedAt = System.nanoTime();
        try {
            return new RequestOutcome(service.getProduct(7L), elapsedMillisSince(startedAt));
        } catch (IllegalStateException exception) {
            return new RequestOutcome(exception.getMessage(), elapsedMillisSince(startedAt));
        }
    }

    private static long elapsedMillisSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static RedissonClient newRedissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }

    private record RequestOutcome(Object result, long elapsedMillis) {
    }

    private static final class DelayedCountingProductRepository implements ProductRepository {
        private final Product product;
        private final AtomicInteger findCalls = new AtomicInteger();

        private DelayedCountingProductRepository(Product product) {
            this.product = product;
        }

        @Override
        public Optional<Product> findById(long id) {
            findCalls.incrementAndGet();
            try {
                Thread.sleep(350L);
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
