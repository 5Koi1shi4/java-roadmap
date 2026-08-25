package com.example.search.application.maintenance;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RebuildJobRepository {
    void insert(RebuildJob job);
    Optional<RebuildJob> find(UUID jobId);
    Optional<RebuildJob> findForUpdate(UUID jobId);
    default boolean hasValidLeaseForUpdate(UUID jobId) {
        return findForUpdate(jobId).map(job -> job.leaseValid(Instant.now())).orElse(false);
    }
    boolean markRunning(UUID jobId, String owner, Instant leaseUntil);
    boolean setStartWatermark(UUID jobId, String owner, long watermark);
    boolean setCatchUp(UUID jobId, String owner, long preparedWatermark, long importedCount);
    boolean addImportedCount(UUID jobId, String owner, long delta);
    boolean renewLease(UUID jobId, String owner, Instant leaseUntil);
    boolean markFailed(UUID jobId, String owner, String reason);

    default boolean markCutover(UUID jobId, String owner) { return false; }
    default boolean markCompleted(UUID jobId, String owner) { return false; }
    default boolean markCompleted(UUID jobId, String owner, long finalWatermark, long differenceCount) {
        return markCompleted(jobId, owner);
    }
    default java.util.List<RebuildJob> findInterrupted() { return java.util.List.of(); }
}
