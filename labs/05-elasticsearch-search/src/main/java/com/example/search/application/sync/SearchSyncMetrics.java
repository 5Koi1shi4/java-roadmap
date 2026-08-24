package com.example.search.application.sync;

import java.time.Duration;

/** Metrics port for synchronization and search operations. */
public interface SearchSyncMetrics {
    void recordDispatch(IndexWriteResult.Outcome outcome, int count, Duration elapsed);

    void recordQuery(boolean success, Duration elapsed);

    void recordRebuild(boolean success, Duration elapsed, long differences);
}
