package com.example.campusmarket.unit.observability;

import com.example.campusmarket.observability.AfterCommitMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AfterCommitMetricsTest {
    @Test
    void defersMetricUntilCommitCallback() {
        AtomicInteger calls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            AfterCommitMetrics.record(calls::incrementAndGet);
            assertThat(calls).hasValue(0);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(calls).hasValue(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
