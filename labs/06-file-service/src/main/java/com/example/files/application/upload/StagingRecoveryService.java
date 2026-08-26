package com.example.files.application.upload;

import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.domain.StoredBlob;
import com.example.files.domain.UploadSession;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 扫描过期 STAGING 会话并以新 owner token 恢复或安全回收。 */
public final class StagingRecoveryService {
    private final UploadSessionRepository sessions;
    private final BlobRepository blobs;
    private final UploadTransactionService transactions;
    private final ObjectStorage storage;
    private final CleanupTaskRepository cleanupTasks;
    private final Duration lease;

    public StagingRecoveryService(UploadSessionRepository sessions, BlobRepository blobs,
                                  UploadTransactionService transactions, ObjectStorage storage,
                                  CleanupTaskRepository cleanupTasks, Duration lease) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.cleanupTasks = Objects.requireNonNull(cleanupTasks, "cleanupTasks");
        if (lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("lease must be positive");
        this.lease = lease;
    }

    public StagingRecoveryService(UploadSessionRepository sessions, BlobRepository blobs,
                                  UploadTransactionService transactions, ObjectStorage storage,
                                  CleanupTaskRepository cleanupTasks,
                                  com.example.files.config.FileServiceProperties properties) {
        this(sessions, blobs, transactions, storage, cleanupTasks,
            Objects.requireNonNull(properties, "properties").stagingLease());
    }

    /** owner 仅用于调用方日志/指标，所有权由数据库随机 token fencing。 */
    public int recoverExpired(int batchSize, String owner) {
        if (batchSize <= 0 || batchSize > 50 || owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("invalid recovery arguments");
        }
        int recovered = 0;
        for (UploadSession candidate : sessions.findExpiredForRecovery(batchSize)) {
            UUID token = UUID.randomUUID();
            if (candidate.blobId() != null && !transactions.claimRecovery(candidate, token, lease)) continue;
            if (candidate.blobId() == null && !sessions.claimExpiredForRecovery(candidate.sessionId(), token, lease)) continue;
            UploadSession claimed = sessions.find(candidate.sessionId()).orElse(null);
            if (claimed == null || !token.equals(claimed.ownerToken())) continue;
            if (claimed.blobId() == null) {
                transactions.markRecoveryFailure(claimed, token, "RECOVERY_BLOB_MISSING", cleanupTasks);
                continue;
            }
            StoredBlob blob = blobs.findById(claimed.blobId()).orElse(null);
            if (blob == null || !matches(claimed, blob)) {
                if (blob != null) {
                    transactions.markRecoveryPending(claimed, blob, token,
                        "RECOVERY_METADATA_MISMATCH", cleanupTasks);
                } else {
                    transactions.markRecoveryFailure(claimed, token, "RECOVERY_BLOB_MISSING", cleanupTasks);
                }
                continue;
            }
            ProbeResult probe = probe(blob);
            if (probe == ProbeResult.STORAGE_UNAVAILABLE) {
                throw new StorageCoordinationUnavailableException("storage unavailable during staging recovery");
            }
            if (probe != ProbeResult.MATCH) {
                transactions.markRecoveryPending(claimed, blob, token,
                    probe == ProbeResult.ABSENT ? "RECOVERY_OBJECT_MISSING" : "RECOVERY_OBJECT_MISMATCH",
                    cleanupTasks);
                continue;
            }
            try {
                transactions.finalizeUpload(claimed.sessionId(), token, blob.id());
                cleanupTasks.enqueueTemp(claimed, claimed.tempKey());
                recovered++;
            } catch (RuntimeException failure) {
                cleanupTasks.enqueueTemp(claimed, claimed.tempKey());
            }
        }
        return recovered;
    }

    private boolean matches(UploadSession session, StoredBlob blob) {
        return (blob.status() == com.example.files.domain.BlobStatus.STAGING
            || blob.status() == com.example.files.domain.BlobStatus.READY)
            && session.actualSize() != null && session.actualSize() == blob.sizeBytes()
            && session.contentHash() != null && session.contentHash().equalsIgnoreCase(blob.contentHash())
            && session.detectedType() == blob.mediaType();
    }

    private ProbeResult probe(StoredBlob blob) {
        try {
            long size = storage.stat(blob.objectKey()).size();
            return size == blob.sizeBytes() ? ProbeResult.MATCH : ProbeResult.SIZE_MISMATCH;
        } catch (StorageObjectNotFoundException notFound) {
            return ProbeResult.ABSENT;
        } catch (RuntimeException unavailable) {
            return ProbeResult.STORAGE_UNAVAILABLE;
        }
    }

    private enum ProbeResult { MATCH, ABSENT, SIZE_MISMATCH, STORAGE_UNAVAILABLE }
}
