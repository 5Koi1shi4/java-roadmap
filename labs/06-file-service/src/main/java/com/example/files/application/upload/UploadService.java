package com.example.files.application.upload;

import com.example.files.application.audit.CorrelationId;
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
        BlobReservation reserve(java.util.UUID sessionId, java.util.UUID ownerToken, InspectedUpload upload);
        BlobReservation resolve(java.util.UUID sessionId, java.util.UUID ownerToken, InspectedUpload upload);
        UploadResult finalizeUpload(BlobReservation.Granted reservation);
        UploadResult attachReadyBlob(BlobReservation.ReadyReuse reservation);
        void recordFailure(java.util.UUID sessionId, java.util.UUID ownerToken, String failureCode);
    }

    private final Transactions transactions;
    private final UploadInspector inspector;
    private final ObjectStorage storage;
    private final StagingWaitPolicy waitPolicy;
    private final CleanupTaskRepository cleanupTasks;
    private final UploadFailureClassifier classifier;
    private final long maxBytes;

    public UploadService(Transactions transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier) {
        this(transactions, inspector, storage, waitPolicy, cleanupTasks, classifier,
            UploadInspector.DEFAULT_MAX_BYTES);
    }

    public UploadService(Transactions transactions, UploadInspector inspector, ObjectStorage storage,
                         StagingWaitPolicy waitPolicy, CleanupTaskRepository cleanupTasks,
                         UploadFailureClassifier classifier, long maxBytes) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.waitPolicy = Objects.requireNonNull(waitPolicy, "waitPolicy");
        this.cleanupTasks = Objects.requireNonNull(cleanupTasks, "cleanupTasks");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        if (maxBytes <= 0 || maxBytes > UploadInspector.DEFAULT_MAX_BYTES) throw new IllegalArgumentException("invalid maxBytes");
        this.maxBytes = maxBytes;
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
            Objects.requireNonNull(properties, "properties").maxBytes());
    }

    public UploadResult upload(UploadCommand command) {
        Objects.requireNonNull(command, "command");
        SafeDisplayName name = SafeDisplayName.from(command.originalName());
        UploadSession session = transactions.begin(command.actorId(), name, command.declaredType());
        TemporaryObject temporary = null;
        try {
            UploadInspection inspection = inspector.open(command.body(), command.originalName(),
                command.declaredType(), command.declaredSize(), maxBytes);
            temporary = storage.writeTemporary(session.tempKey(), inspection.stream(), maxBytes);
            InspectedUpload inspected = inspection.finish(temporary);
            BlobReservation reservation = transactions.reserve(session.sessionId(), session.ownerToken(), inspected);
            if (reservation instanceof BlobReservation.Waiting) {
                reservation = waitPolicy.awaitOwnershipOrReady(reservation,
                    () -> transactions.resolve(session.sessionId(), session.ownerToken(), inspected));
            }
            UploadResult result;
            if (reservation instanceof BlobReservation.ReadyReuse ready) {
                result = transactions.attachReadyBlob(ready);
            } else if (reservation instanceof BlobReservation.Owned owned) {
                storage.commit(temporary.key(), owned.objectKey());
                result = transactions.finalizeUpload(owned);
            } else {
                throw new StorageCoordinationUnavailableException("no upload reservation capability");
            }
            deleteOrEnqueue(session, temporary);
            return result;
        } catch (RuntimeException failure) {
            try {
                transactions.recordFailure(session.sessionId(), session.ownerToken(), classifier.classify(failure));
            } catch (RuntimeException ignored) {
                // 原始上传错误优先返回，失败记录由后续恢复扫描补齐。
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
            throw failure;
        }
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
}
