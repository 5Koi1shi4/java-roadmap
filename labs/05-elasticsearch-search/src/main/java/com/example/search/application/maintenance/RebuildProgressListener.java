package com.example.search.application.maintenance;

import java.util.UUID;

@FunctionalInterface
public interface RebuildProgressListener {
    RebuildProgressListener NOOP = (jobId, watermark) -> { };

    void afterStartWatermark(UUID jobId, long watermark);
}
