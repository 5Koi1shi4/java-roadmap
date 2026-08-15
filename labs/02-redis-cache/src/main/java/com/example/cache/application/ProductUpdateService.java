package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;

public final class ProductUpdateService {

    private final ProductRepository repository;
    private final ProductCache cache;
    private final TransactionCallbacks transactionCallbacks;

    public ProductUpdateService(
            ProductRepository repository,
            ProductCache cache,
            TransactionCallbacks transactionCallbacks) {
        this.repository = repository;
        this.cache = cache;
        this.transactionCallbacks = transactionCallbacks;
    }

    public void updateProduct(UpdateProductCommand command) {
        repository.update(new Product(command.id(), command.name(), command.priceInCents()));
        transactionCallbacks.afterCommit(() -> cache.evict(command.id()));
    }

    public interface TransactionCallbacks {

        void afterCommit(Runnable callback);
    }
}
