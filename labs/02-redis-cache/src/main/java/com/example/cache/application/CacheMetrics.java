package com.example.cache.application;

import java.time.Duration;

public interface CacheMetrics {

    CacheMetrics NO_OP = new CacheMetrics() {
        @Override
        public void recordHit() {
        }

        @Override
        public void recordMiss() {
        }

        @Override
        public void recordNegativeHit() {
        }

        @Override
        public void recordRepositoryLoad() {
        }

        @Override
        public void recordLockBusy() {
        }

        @Override
        public void recordLockWait(Duration duration) {
        }
    };

    void recordHit();

    void recordMiss();

    void recordNegativeHit();

    void recordRepositoryLoad();

    void recordLockBusy();

    void recordLockWait(Duration duration);
}
