package com.example.search.application.maintenance;

import java.time.Instant;
import java.util.UUID;

public record RebuildJob(UUID jobId, String targetIndex, RebuildStatus status, RebuildPhase phase,
                         String owner, Instant leaseUntil, Long startWatermark, Long finalWatermark,
                         long importedCount, long differenceCount, String lastError,
                         Instant createdAt, Instant completedAt, String sourceIndex) {
    public RebuildJob {
        if (jobId == null || targetIndex == null || targetIndex.isBlank() || status == null || phase == null
                || owner == null || owner.isBlank() || owner.length() > 128 || leaseUntil == null || createdAt == null) {
            throw new IllegalArgumentException("invalid rebuild job");
        }
        if (importedCount < 0 || differenceCount < 0) throw new IllegalArgumentException("counts must not be negative");
        if (sourceIndex != null && sourceIndex.isBlank()) throw new IllegalArgumentException("source index must not be blank");
    }

    /** Compatibility constructor for jobs created before source-index fencing was introduced. */
    public RebuildJob(UUID jobId, String targetIndex, RebuildStatus status, RebuildPhase phase,
                      String owner, Instant leaseUntil, Long startWatermark, Long finalWatermark,
                      long importedCount, long differenceCount, String lastError,
                      Instant createdAt, Instant completedAt) {
        this(jobId, targetIndex, status, phase, owner, leaseUntil, startWatermark, finalWatermark,
                importedCount, differenceCount, lastError, createdAt, completedAt, null);
    }

    public boolean leaseValid(Instant now) {
        return leaseUntil.isAfter(now);
    }
}
