package com.example.search.application.maintenance;

import java.util.UUID;

public record PreparedRebuild(UUID jobId, String targetIndex, long startWatermark,
                              long preparedWatermark, long importedCount) {
    public PreparedRebuild {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        if (targetIndex == null || targetIndex.isBlank()) throw new IllegalArgumentException("targetIndex is required");
        if (startWatermark < 0 || preparedWatermark < startWatermark || importedCount < 0) {
            throw new IllegalArgumentException("invalid rebuild watermarks or count");
        }
    }
}
