package com.example.files.application.cleanup;

import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.application.upload.UploadSessionRepository;
import com.example.files.domain.UploadSession;
import com.example.files.domain.UploadSessionStatus;
import com.example.files.application.upload.BlobRepository;
import com.example.files.application.audit.FileServiceMetrics;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 以数据库租约领取任务，并在事务外执行对象存储删除。 */
public final class StorageCleanupService {
    private final CleanupTaskRepository tasks;
    private final ObjectStorage storage;
    private final UploadSessionRepository sessions;
    private final BlobRepository blobs;
    private final int batchSize;
    private final Duration lease;
    private final CleanupRetrySchedule retrySchedule;
    private final CleanupFailureClassifier failures = new CleanupFailureClassifier();
    private final FileServiceMetrics metrics;

    public StorageCleanupService(CleanupTaskRepository tasks, ObjectStorage storage,
                                 UploadSessionRepository sessions, BlobRepository blobs,
                                 int batchSize, Duration lease, CleanupRetrySchedule retrySchedule) {
        this(tasks, storage, sessions, blobs, batchSize, lease, retrySchedule, null);
    }

    public StorageCleanupService(CleanupTaskRepository tasks, ObjectStorage storage,
                                 UploadSessionRepository sessions, BlobRepository blobs,
                                 int batchSize, Duration lease, CleanupRetrySchedule retrySchedule,
                                 FileServiceMetrics metrics) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        if (batchSize <= 0 || batchSize > 50 || lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("invalid cleanup configuration");
        this.batchSize = batchSize;
        this.lease = lease;
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
        this.metrics = metrics;
    }

    public StorageCleanupService(CleanupTaskRepository tasks, ObjectStorage storage,
                                 UploadSessionRepository sessions, BlobRepository blobs,
                                 com.example.files.config.FileServiceProperties properties) {
        this(tasks, storage, sessions, blobs, Objects.requireNonNull(properties, "properties").cleanup().batchSize(),
            properties.cleanup().lease(), new CleanupRetrySchedule(properties.cleanup().retryDelays(), properties.cleanup().maxAttempts()), null);
    }

    public StorageCleanupService(CleanupTaskRepository tasks, ObjectStorage storage,
                                 UploadSessionRepository sessions, BlobRepository blobs,
                                 com.example.files.config.FileServiceProperties properties,
                                 FileServiceMetrics metrics) {
        this(tasks, storage, sessions, blobs, Objects.requireNonNull(properties, "properties").cleanup().batchSize(),
            properties.cleanup().lease(), new CleanupRetrySchedule(properties.cleanup().retryDelays(), properties.cleanup().maxAttempts()), metrics);
    }

    /** owner 是内部 worker 标识，不作为资源凭据；token 始终由本服务随机产生。 */
    public CleanupSummary runBatch(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) throw new IllegalArgumentException("invalid cleanup owner");
        var claimed = tasks.claimBatch(owner, batchSize, lease);
        int completed = 0, retried = 0, failed = 0;
        for (ClaimedCleanup task : claimed) {
            Outcome outcome;
            try {
                outcome = task.type() == CleanupTaskType.TEMP_OBJECT ? cleanTemp(task) : cleanBlob(task);
            } catch (RuntimeException error) {
                outcome = failure(task, error);
            }
            if (outcome == Outcome.COMPLETED) completed++;
            else if (outcome == Outcome.RETRIED) retried++;
            else if (outcome == Outcome.FAILED) failed++;
            if (metrics != null) {
                try { metrics.recordCleanupRetry(task.type().name(), outcome == Outcome.COMPLETED ? "success" : outcome == Outcome.RETRIED ? "retry" : "failed"); }
                catch (RuntimeException ignored) { }
            }
        }
        return new CleanupSummary(claimed.size(), completed, retried, failed);
    }

    private Outcome cleanTemp(ClaimedCleanup task) {
        UUID sessionId;
        try { sessionId = UUID.fromString(task.targetId()); } catch (IllegalArgumentException bad) { return markFailure(task, bad); }
        UploadSession session = sessions.find(sessionId).orElse(null);
        if (session == null || !task.objectKey().equals(session.tempKey())) return markRetry(task, "cleanup target mismatch");
        if (terminal(session.status())) return deleteAndComplete(task);
        // RECEIVING 永远不能被扫描删除；VALIDATED/FINALIZING 只能在数据库租约条件接管后清理。
        if (session.status() == UploadSessionStatus.RECEIVING) return markRetry(task, "active upload session");
        if (!sessions.isExpiredAtDatabaseTime(sessionId)) return markRetry(task, "upload session ttl is active");
        UUID takeover = UUID.randomUUID();
        if (!sessions.claimExpiredForRecovery(sessionId, session.ownerToken(), session.tempKey(), takeover, lease)) {
            return markRetry(task, "upload session lease or fencing condition is not satisfied");
        }
        UploadSession claimed = sessions.find(sessionId).orElse(null);
        if (claimed == null || !takeover.equals(claimed.ownerToken()) || !task.objectKey().equals(claimed.tempKey())) {
            return markRetry(task, "upload session takeover fenced");
        }
        return deleteAndComplete(task);
    }

    private Outcome cleanBlob(ClaimedCleanup task) {
        long blobId;
        try { blobId = Long.parseLong(task.targetId()); } catch (NumberFormatException bad) { return markFailure(task, bad); }
        UUID token = UUID.randomUUID();
        if (!blobs.claimDeletion(blobId, task.targetGeneration(), task.objectKey(), token, lease)) return markRetry(task, "blob deletion claim fenced");
        boolean deleted = false;
        try {
            storage.delete(task.objectKey());
            deleted = true;
        } catch (StorageObjectNotFoundException absent) {
            deleted = true; // 删除幂等：不存在即成功。
        } catch (RuntimeException error) {
            return failure(task, error);
        }
        if (!deleted || !tasks.completeBlobAndTask(blobId, task.targetGeneration(), task.objectKey(), token,
            task.taskId(), task.claimToken())) {
            return markRetry(task, "blob deletion completion fenced");
        }
        return Outcome.COMPLETED;
    }

    private Outcome deleteAndComplete(ClaimedCleanup task) {
        try { storage.delete(task.objectKey()); }
        catch (StorageObjectNotFoundException ignored) { }
        catch (RuntimeException error) { return failure(task, error); }
        return tasks.complete(task.taskId(), task.claimToken()) ? Outcome.COMPLETED : Outcome.RETRIED;
    }

    private Outcome failure(ClaimedCleanup task, RuntimeException error) {
        if (failures.retryable(error)) return markRetry(task, "storage cleanup retryable failure");
        return markFailure(task, error);
    }
    private Outcome markRetry(ClaimedCleanup task, String reason) {
        int next = task.attemptCount() + 1;
        if (!retrySchedule.shouldRetry(next)) return markFailure(task, new IllegalStateException(reason));
        return tasks.retry(task.taskId(), task.claimToken(), retrySchedule.delayAfter(next), reason)
            ? Outcome.RETRIED : Outcome.RETRIED;
    }
    private Outcome markFailure(ClaimedCleanup task, RuntimeException error) {
        return tasks.fail(task.taskId(), task.claimToken(), "cleanup failed") ? Outcome.FAILED : Outcome.FAILED;
    }
    private static boolean terminal(UploadSessionStatus status) {
        return status == UploadSessionStatus.COMPLETED || status == UploadSessionStatus.FAILED || status == UploadSessionStatus.EXPIRED;
    }
    private enum Outcome { COMPLETED, RETRIED, FAILED }
}
