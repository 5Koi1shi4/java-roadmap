package com.example.search.application.sync;

import java.time.Duration;

/** Metrics port for synchronization and search operations. */
public interface SearchSyncMetrics {
    void recordDispatch(IndexWriteResult.Outcome outcome, int count, Duration elapsed);

    void recordQuery(boolean success, Duration elapsed);

    void recordRebuild(boolean success, Duration elapsed, long differences);

    static SearchSyncMetrics noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements SearchSyncMetrics {
        INSTANCE;
        @Override public void recordDispatch(IndexWriteResult.Outcome outcome, int count, Duration elapsed) { }
        @Override public void recordQuery(boolean success, Duration elapsed) { }
        @Override public void recordRebuild(boolean success, Duration elapsed, long differences) { }
    }
}
