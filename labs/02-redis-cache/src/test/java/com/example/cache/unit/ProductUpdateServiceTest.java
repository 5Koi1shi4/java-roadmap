package com.example.cache.unit;

import com.example.cache.application.ProductUpdateService;
import com.example.cache.application.UpdateProductCommand;
import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import com.example.cache.infrastructure.SpringTransactionCallbacks;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductUpdateServiceTest {

    @Test
    void evictsCachedProductAfterTheProductionTransactionTemplateCommits() {
        Product existing = new Product(7L, "Java 编程思想", 99_00L);
        Product updated = new Product(7L, "Effective Java", 88_00L);
        InMemoryProductRepository repository = new InMemoryProductRepository(existing);
        InMemoryProductCache cache = new InMemoryProductCache(existing);
        ProductUpdateService service = new ProductUpdateService(
                repository,
                cache,
                new SpringTransactionCallbacks(),
                new TransactionTemplate(new CommitOnlyTransactionManager()));

        service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L));

        assertThat(repository.findById(7L)).contains(updated);
        assertThat(cache.get(7L)).isEqualTo(ProductCache.CacheLookup.miss());
    }

    @Test
    void retainsCachedProductUntilTheDatabaseTransactionCommits() {
        Product existing = new Product(7L, "Java 编程思想", 99_00L);
        Product updated = new Product(7L, "Effective Java", 88_00L);
        InMemoryProductRepository repository = new InMemoryProductRepository(existing);
        InMemoryProductCache cache = new InMemoryProductCache(existing);
        ControlledTransactionCallbacks transactionCallbacks = new ControlledTransactionCallbacks();
        ProductUpdateService service = new ProductUpdateService(repository, cache, transactionCallbacks);

        service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L));

        assertThat(repository.findById(7L)).contains(updated);
        assertThat(cache.get(7L)).isEqualTo(ProductCache.CacheLookup.product(existing));

        transactionCallbacks.commit();

        assertThat(cache.get(7L)).isEqualTo(ProductCache.CacheLookup.miss());
    }

    @Test
    void retainsCachedProductWhenTheDatabaseTransactionRollsBack() {
        Product existing = new Product(7L, "Java 编程思想", 99_00L);
        InMemoryProductRepository repository = new InMemoryProductRepository(existing);
        InMemoryProductCache cache = new InMemoryProductCache(existing);
        ControlledTransactionCallbacks transactionCallbacks = new ControlledTransactionCallbacks();
        ProductUpdateService service = new ProductUpdateService(repository, cache, transactionCallbacks);

        service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L));
        transactionCallbacks.rollback();

        assertThat(cache.get(7L)).isEqualTo(ProductCache.CacheLookup.product(existing));
    }

    @Test
    void retainsCachedProductWhenRepositoryUpdateFails() {
        Product existing = new Product(7L, "Java 编程思想", 99_00L);
        InMemoryProductCache cache = new InMemoryProductCache(existing);
        ControlledTransactionCallbacks transactionCallbacks = new ControlledTransactionCallbacks();
        ProductUpdateService service = new ProductUpdateService(new FailingProductRepository(), cache, transactionCallbacks);

        assertThatThrownBy(() -> service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        transactionCallbacks.commit();

        assertThat(cache.get(7L)).isEqualTo(ProductCache.CacheLookup.product(existing));
    }

    @Test
    void keepsUpdatedProductWhenCacheEvictionFailsAfterCommit() {
        Product existing = new Product(7L, "Java 编程思想", 99_00L);
        Product updated = new Product(7L, "Effective Java", 88_00L);
        InMemoryProductRepository repository = new InMemoryProductRepository(existing);
        ControlledTransactionCallbacks transactionCallbacks = new ControlledTransactionCallbacks();
        ProductUpdateService service = new ProductUpdateService(repository, new FailingProductCache(), transactionCallbacks);

        service.updateProduct(new UpdateProductCommand(7L, "Effective Java", 88_00L));

        assertThatThrownBy(transactionCallbacks::commit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");
        assertThat(repository.findById(7L)).contains(updated);
    }

    @Test
    void rejectsInvalidUpdateCommands() {
        assertThatThrownBy(() -> new UpdateProductCommand(0L, "Effective Java", 88_00L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("product id must be positive");
        assertThatThrownBy(() -> new UpdateProductCommand(7L, " ", 88_00L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("product name must not be blank");
        assertThatThrownBy(() -> new UpdateProductCommand(7L, "Effective Java", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("product price must not be negative");
    }

    private static final class InMemoryProductRepository implements ProductRepository {
        private final Map<Long, Product> products = new HashMap<>();

        private InMemoryProductRepository(Product product) {
            products.put(product.id(), product);
        }

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(products.get(id));
        }

        @Override
        public void update(Product product) {
            products.put(product.id(), product);
        }
    }

    private static final class InMemoryProductCache implements ProductCache {
        private final Map<Long, CacheLookup> entries = new HashMap<>();

        private InMemoryProductCache(Product product) {
            put(product);
        }

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

    private static final class FailingProductRepository implements ProductRepository {

        @Override
        public Optional<Product> findById(long id) {
            return Optional.empty();
        }

        @Override
        public void update(Product product) {
            throw new IllegalStateException("database unavailable");
        }
    }

    private static final class FailingProductCache implements ProductCache {

        @Override
        public CacheLookup get(long id) {
            return CacheLookup.miss();
        }

        @Override
        public void put(Product product) {
        }

        @Override
        public void putNegative(long id) {
        }

        @Override
        public void evict(long id) {
            throw new IllegalStateException("redis unavailable");
        }
    }

    private static final class ControlledTransactionCallbacks implements ProductUpdateService.TransactionCallbacks {
        private Runnable afterCommit;

        @Override
        public void afterCommit(Runnable callback) {
            afterCommit = callback;
        }

        void commit() {
            if (afterCommit != null) {
                afterCommit.run();
            }
        }

        void rollback() {
        }
    }

    private static final class CommitOnlyTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
