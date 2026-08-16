package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import org.springframework.transaction.support.TransactionTemplate;

public final class ProductUpdateService {

    private final ProductRepository repository;
    private final ProductCache cache;
    private final TransactionCallbacks transactionCallbacks;
    private final TransactionTemplate transactions;

    public ProductUpdateService(
            ProductRepository repository,
            ProductCache cache,
            TransactionCallbacks transactionCallbacks) {
        this(repository, cache, transactionCallbacks, null);
    }

    public ProductUpdateService(
            ProductRepository repository,
            ProductCache cache,
            TransactionCallbacks transactionCallbacks,
            TransactionTemplate transactions) {
        this.repository = repository;
        this.cache = cache;
        this.transactionCallbacks = transactionCallbacks;
        this.transactions = transactions;
    }

    public void updateProduct(UpdateProductCommand command) {
        if (transactions == null) {
            updateInsideTransaction(command);
            return;
        }
        transactions.executeWithoutResult(status -> updateInsideTransaction(command));
    }

    private void updateInsideTransaction(UpdateProductCommand command) {
        repository.update(new Product(command.id(), command.name(), command.priceInCents()));
        transactionCallbacks.afterCommit(() -> cache.evict(command.id()));
    }

    public interface TransactionCallbacks {

        void afterCommit(Runnable callback);
    }
}
