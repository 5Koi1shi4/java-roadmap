package com.example.campusmarket.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Executes a business-success metric only after the surrounding transaction commits. */
public final class AfterCommitMetrics {
    private AfterCommitMetrics() { }

    public static void record(Runnable metric) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metric.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { metric.run(); }
        });
    }
}
