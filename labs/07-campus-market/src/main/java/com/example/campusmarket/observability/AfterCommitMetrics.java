package com.example.campusmarket.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅在外围事务提交成功后记录业务成功指标。 */
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
