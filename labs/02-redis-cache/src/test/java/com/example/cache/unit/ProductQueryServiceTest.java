package com.example.cache.unit;

import com.example.cache.application.ProductQueryService;
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

    private static final class InMemoryProductRepository implements ProductRepository {
        private final Map<Long, Product> products = new HashMap<>();
        private int findCalls;

        @Override
        public Optional<Product> findById(long id) {
            findCalls++;
            return Optional.ofNullable(products.get(id));
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

        void clear() {
            entries.clear();
            getCalls = 0;
        }
    }
}
