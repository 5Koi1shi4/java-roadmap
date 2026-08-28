package com.example.files.application.upload;

import com.example.files.application.audit.CorrelationId;
import com.example.files.application.audit.FileServiceMetrics;
import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSession;
import com.example.files.config.FileServiceProperties;

import java.time.Duration;
import java.util.Objects;

/** 上传 A/B/C 短事务与临时对象生命周期的应用编排。 */
public final class UploadService {
    /** 事务端口只包含短数据库操作，严禁实现方在这些方法中执行对象存储 IO。 */
    public interface Transactions {
        UploadSession begin(long actorId, SafeDisplayName name, String declaredType);
        default UploadSession begin(long actorId, SafeDisplayName name, String declaredType,
                                    Duration sessionTtl, Duration stagingLease) {
            return begin(actorId, name, declaredType);
        }
        BlobReservation reserve(java.util.UUID sessionId, java.util.UUID ownerToken, InspectedUpload upload);
        BlobReservation resolve(java.util.UUID sessionId, java.util.UUID ownerToken, InspectedUpload upload);
        UploadResult finalizeUpload(BlobReservation.Granted reservation);
        UploadResult finalizeUpload(BlobReservation.Granted reservation, CorrelationId correlationId);
        UploadResult attachReadyBlob(BlobReservation.ReadyReuse reservation);
        UploadResult attachReadyBlob(BlobReservation.ReadyReuse reservation, CorrelationId correlationId);
        void recordFailure(java.util.UUID sessionId, java.util.UUID ownerToken, String failureCode);

        /** 等待者只续自己的 session lease，不能延长其他 blob owner。 */
        default boolean renewWaiting(java.util.UUID sessionId, java.util.UUID ownerToken) { return true; }

        /** Owned 提交前必须以 session+blob token 双 fencing 续租。 */
        default boolean renewOwned(BlobReservation.Owned reservation) { return true; }
    }

    private final Transactions transactions;
    private final UploadInspector inspector;
    private final ObjectStorage storage;
    private final StagingWaitPolicy waitPolicy;
    private final CleanupTaskRepository cleanupTasks;
    private final UploadFailureClassifier classifier;
    private final long maxBytes;
    private final Duration sessionTtl;
    private final Duration stagingLease;
    private final FileServiceMetrics metrics;

    public UploadService(Transactions transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier) {
        this(transactions, inspector, storage, waitPolicy, cleanupTasks, classifier,
            UploadInspector.DEFAULT_MAX_BYTES, null, null, null);
    }

    public UploadService(Transactions transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier, long maxBytes) {
        this(transactions, inspector, storage, waitPolicy, cleanupTasks, classifier, maxBytes, null, null, null);
    }

    private UploadService(Transactions transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier, long maxBytes,
                         Duration sessionTtl, Duration stagingLease, FileServiceMetrics metrics) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.waitPolicy = Objects.requireNonNull(waitPolicy, "waitPolicy");
        this.cleanupTasks = Objects.requireNonNull(cleanupTasks, "cleanupTasks");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        if (maxBytes <= 0 || maxBytes > UploadInspector.DEFAULT_MAX_BYTES) throw new IllegalArgumentException("invalid maxBytes");
        this.maxBytes = maxBytes;
        this.sessionTtl = sessionTtl;
        this.stagingLease = stagingLease;
        this.metrics = metrics == null ? new NoopMetrics() : metrics;
    }

    public UploadService(UploadTransactionService transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier) {
        this((Transactions) transactions, inspector, storage, waitPolicy, cleanupTasks, classifier);
    }

    public UploadService(UploadTransactionService transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier, FileServiceProperties properties) {
        this((Transactions) transactions, inspector, storage, waitPolicy, cleanupTasks, classifier,
            Objects.requireNonNull(properties, "properties").maxBytes(), properties.uploadSessionTtl(), properties.stagingLease(), null);
    }

    public UploadService(UploadTransactionService transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier, FileServiceProperties properties,
                         FileServiceMetrics metrics) {
        this((Transactions) transactions, inspector, storage, waitPolicy, cleanupTasks, classifier,
            Objects.requireNonNull(properties, "properties").maxBytes(), properties.uploadSessionTtl(),
            properties.stagingLease(), metrics);
    }

    public UploadResult upload(UploadCommand command) {
        Objects.requireNonNull(command, "command");
        long started = System.nanoTime();
        SafeDisplayName name = SafeDisplayName.from(command.originalName());
        UploadSession session = sessionTtl == null
            ? transactions.begin(command.actorId(), name, command.declaredType())
            : transactions.begin(command.actorId(), name, command.declaredType(), sessionTtl, stagingLease);
        TemporaryObject temporary = null;
        BlobReservation reservation = null;
        try {
            UploadInspection inspection = inspector.open(command.body(), command.originalName(),
                command.declaredType(), command.declaredSize(), maxBytes);
            temporary = storage.writeTemporary(session.tempKey(), inspection.stream(), maxBytes);
            InspectedUpload inspected = inspection.finish(temporary);
            reservation = transactions.reserve(session.sessionId(), session.ownerToken(), inspected);
            if (reservation instanceof BlobReservation.Waiting) {
                reservation = waitPolicy.awaitOwnershipOrReady(reservation,
                    () -> transactions.resolve(session.sessionId(), session.ownerToken(), inspected),
                    () -> transactions.renewWaiting(session.sessionId(), session.ownerToken()));
            }
            UploadResult result;
            if (reservation instanceof BlobReservation.ReadyReuse ready) {
                result = transactions.attachReadyBlob(ready, command.correlationId());
            } else if (reservation instanceof BlobReservation.Owned owned) {
                if (!transactions.renewOwned(owned)) {
                    throw new StorageCoordinationUnavailableException("upload lease lost before object commit");
                }
                storage.commit(temporary.key(), owned.objectKey());
                result = transactions.finalizeUpload(owned, command.correlationId());
            } else {
                throw new StorageCoordinationUnavailableException("no upload reservation capability");
            }
            deleteOrEnqueue(session, temporary);
            observeUpload("success", started);
            return result;
        } catch (RuntimeException failure) {
            // 物理提交失败或 C 失败必须保留 VALIDATED/STAGING，供恢复扫描判断对象是否存在。
            if (!(reservation instanceof BlobReservation.Owned)) {
                try {
                    transactions.recordFailure(session.sessionId(), session.ownerToken(), classifier.classify(failure));
                } catch (RuntimeException ignored) {
                    // 原始上传错误优先返回，失败记录由后续恢复扫描补齐。
                }
            }
            if (temporary != null) {
                safeEnqueueTemp(session, temporary.key());
            } else {
                try {
                    cleanupTasks.enqueueTempIfEligible(session.sessionId());
                } catch (RuntimeException ignored) {
                    // 补偿系统故障不能覆盖原始上传失败。
                }
            }
            if (reservation instanceof BlobReservation.Owned owned) safeEnqueueBlob(owned);
            observeUpload(failure instanceof UploadRejectedException ? "rejected" : "failed", started);
            throw failure;
        }
    }

    private void observeUpload(String result, long started) {
        try { metrics.recordUpload(result, Duration.ofNanos(System.nanoTime() - started)); }
        catch (RuntimeException ignored) { /* 指标故障不得改变业务终态。 */ }
    }

    private void deleteOrEnqueue(UploadSession session, TemporaryObject temporary) {
        try {
            storage.delete(temporary.key());
        } catch (RuntimeException cleanupFailure) {
            safeEnqueueTemp(session, temporary.key());
        }
    }

    private void safeEnqueueTemp(UploadSession session, String key) {
        try {
            cleanupTasks.enqueueTemp(session, key);
        } catch (RuntimeException ignored) {
            // 入队具有幂等语义；本次不可用时交由后续扫描补齐。
        }
    }

    private void safeEnqueueBlob(BlobReservation.Granted reservation) {
        try {
            cleanupTasks.enqueueBlob(reservation);
        } catch (RuntimeException ignored) {
            // 恢复扫描仍可依据 STAGING 租约重试；本次入队故障不得覆盖上传错误。
        }
    }

    private static final class NoopMetrics implements FileServiceMetrics {
        @Override public void recordUpload(String result, Duration duration) { }
        @Override public void recordSession(String status) { }
        @Override public void setSessionCount(String status, long count) { }
        @Override public void recordBlob(String status) { }
        @Override public void setBlobCount(String status, long count) { }
        @Override public void setStagingOldest(Duration age) { }
        @Override public void recordCleanupPending(String type) { }
        @Override public void setCleanupPending(String type, long count) { }
        @Override public void recordCleanupRetry(String type, String result) { }
        @Override public void recordDownload(String phase, String result) { }
        @Override public void recordAcl(String action, String result) { }
        @Override public void recordStorageOperation(String operation, String result, Duration duration) { }
    }
}
