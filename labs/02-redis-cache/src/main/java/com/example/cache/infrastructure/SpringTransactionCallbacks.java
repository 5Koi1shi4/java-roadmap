package com.example.cache.infrastructure;

import com.example.cache.application.ProductUpdateService.TransactionCallbacks;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SpringTransactionCallbacks implements TransactionCallbacks {

    @Override
    public void afterCommit(Runnable callback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("an active Spring transaction is required");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }
}
