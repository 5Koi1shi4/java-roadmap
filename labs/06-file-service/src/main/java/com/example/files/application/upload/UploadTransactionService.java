package com.example.files.application.upload;

import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.config.FileServiceProperties;
import com.example.files.domain.BlobStatus;
import com.example.files.domain.StoredBlob;
import com.example.files.domain.StoredFile;
import com.example.files.domain.UploadSession;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/** 编排上传会话、Blob 和逻辑文件的短事务；事务内不执行对象存储 IO。 */
public final class UploadTransactionService implements UploadService.Transactions {
    private final UploadSessionRepository sessions;
    private final BlobRepository blobs;
    private final FileRepository files;
    private final AuditRecorder audits;
    private final TransactionTemplate transactionTemplate;
    private Duration configuredTtl;
    private Duration configuredLease;

    public UploadTransactionService(UploadSessionRepository sessions, BlobRepository blobs,
                                    FileRepository files, AuditRecorder audits,
                                    TransactionTemplate transactionTemplate) {
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.blobs = java.util.Objects.requireNonNull(blobs, "blobs");
        this.files = java.util.Objects.requireNonNull(files, "files");
        this.audits = java.util.Objects.requireNonNull(audits, "audits");
        this.transactionTemplate = java.util.Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.configuredTtl = null;
        this.configuredLease = null;
    }

    /** 生产编排必须注入类型安全配置，避免隐式租约和等待窗口。 */
    public UploadTransactionService(UploadSessionRepository sessions, BlobRepository blobs,
                                    FileRepository files, AuditRecorder audits,
                                    TransactionTemplate transactionTemplate,
                                    FileServiceProperties properties) {
        this(sessions, blobs, files, audits, transactionTemplate);
        FileServiceProperties configured = java.util.Objects.requireNonNull(properties, "properties");
        this.configuredTtl = configured.uploadSessionTtl();
        this.configuredLease = configured.stagingLease();
    }

    /** 禁止省略 TTL；调用方必须从 FileServiceProperties 显式传入。 */
    public UploadSession begin(long uploaderId, com.example.files.domain.SafeDisplayName name,
                               String declaredType) {
        if (configuredTtl == null || configuredLease == null) {
            throw new IllegalStateException("upload TTL and staging lease must be configured explicitly");
        }
        return begin(uploaderId, name, declaredType, configuredTtl, configuredLease);
    }

    @Override
    public UploadSession begin(long uploaderId, com.example.files.domain.SafeDisplayName name,
                               String declaredType, Duration ttl, Duration lease) {
        String tempKey = "tmp/" + UUID.randomUUID();
        UUID ownerToken = UUID.randomUUID();
        return sessions.create(uploaderId, tempKey, ownerToken, name, declaredType, ttl, lease);
    }

    /** 在验证阶段持久化真实大小、类型和哈希并领取物理 Blob。 */
    public BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease) {
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                return transactionTemplate.execute(status -> reserveInTransaction(sessionId, ownerToken, upload, lease));
            } catch (BlobHashConflictException conflict) {
                // 唯一键冲突事务已经结束；新事务用 current locking read 按哈希重新领取。
                BlobReservation existing = transactionTemplate.execute(status -> resolveExistingForUpdate(sessionId, ownerToken, upload));
                if (existing != null) {
                    return existing;
                }
            }
        }
        throw new IllegalStateException("BLOB_HASH_COORDINATION_RETRY_EXHAUSTED");
    }

    private BlobReservation reserveInTransaction(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease) {
        validateSession(sessionId, ownerToken, upload);
        BlobReservation reservation = blobs.reserve(sessionId, ownerToken, upload, lease);
        bindReservation(sessionId, ownerToken, reservation);
        return reservation;
    }

    private BlobReservation resolveExistingForUpdate(UUID sessionId, UUID ownerToken, InspectedUpload upload) {
        validateSession(sessionId, ownerToken, upload);
        BlobReservation current = blobs.findByHashForUpdate(upload.sha256())
            .map(blob -> reservationFor(blob, sessionId, ownerToken)).orElse(null);
        if (current != null) bindReservation(sessionId, ownerToken, current);
        return current;
    }

    private BlobReservation reservationFor(StoredBlob blob, UUID sessionId, UUID ownerToken) {
        if (blob.status() == BlobStatus.READY) return BlobReservation.readyReuse(sessionId, ownerToken, blob.id(), blob.objectKey());
        if (blob.status() == BlobStatus.STAGING && sessionId.equals(blob.stagingSessionId())
            && ownerToken.equals(blob.stagingOwnerToken())) return BlobReservation.ownedStaging(sessionId, ownerToken, blob.id(), blob.objectKey());
        return BlobReservation.waiting();
    }

    private void validateSession(UUID sessionId, UUID ownerToken, InspectedUpload upload) {
        if (!sessions.markValidated(sessionId, ownerToken, upload)) {
            UploadSession existing = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("UPLOAD_SESSION_NOT_FOUND"));
            if (existing.status() != com.example.files.domain.UploadSessionStatus.VALIDATED
                || existing.contentHash() == null || !existing.contentHash().equalsIgnoreCase(upload.sha256())
                || existing.actualSize() == null || existing.actualSize() != upload.size()
                || existing.detectedType() != upload.detectedType()) {
                throw new IllegalStateException("UPLOAD_SESSION_NOT_VALIDATED");
            }
        }
    }

    private void bindReservation(UUID sessionId, UUID ownerToken, BlobReservation reservation) {
        if (reservation instanceof BlobReservation.Granted granted
            && !sessions.bindBlob(sessionId, ownerToken, granted.blobId())) {
            throw new IllegalStateException("UPLOAD_BLOB_BINDING_FAILED");
        }
    }

    public BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload) {
        if (configuredLease == null) throw new IllegalStateException("staging lease must be configured explicitly");
        return reserve(sessionId, ownerToken, upload, configuredLease);
    }

    /** 以调用方最新校验结果重新解析领取状态，等待结果本身不作为后续能力使用。 */
    public BlobReservation resolve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease) {
        BlobReservation reservation = reserve(sessionId, ownerToken, upload, lease);
        if (!(reservation instanceof BlobReservation.Waiting)) return reservation;
        StoredBlob hint = blobs.findByHash(upload.sha256()).orElse(null);
        BlobReservation resolved = transactionTemplate.execute(status -> resolveWaitingInTransaction(sessionId, ownerToken, upload, lease, hint));
        return resolved == null ? reservation : resolved;
    }

    private BlobReservation resolveWaitingInTransaction(UUID sessionId, UUID ownerToken,
                                                        InspectedUpload upload, Duration lease, StoredBlob hint) {
        if (hint == null) return null;
        UUID oldId = hint.stagingSessionId();
        if (hint.status() == BlobStatus.STAGING && oldId != null && hint.stagingOwnerToken() != null
            && !sessionId.equals(oldId)) {
            UUID first = oldId.toString().compareTo(sessionId.toString()) < 0 ? oldId : sessionId;
            UUID second = first.equals(oldId) ? sessionId : oldId;
            UploadSession firstSession = sessions.findForUpdate(first).orElse(null);
            UploadSession secondSession = sessions.findForUpdate(second).orElse(null);
            if (firstSession == null || secondSession == null) return null;
            StoredBlob blob = blobs.findByHashForUpdate(upload.sha256()).orElse(null);
            if (blob == null || blob.status() != BlobStatus.STAGING || !oldId.equals(blob.stagingSessionId())
                || !hint.stagingOwnerToken().equals(blob.stagingOwnerToken())) return BlobReservation.waiting();
            if (!sessions.takeOverExpired(oldId, hint.stagingOwnerToken(), sessionId, ownerToken, blob.id(), lease)
                || !blobs.takeOverExpiredStaging(blob.id(), oldId, hint.stagingOwnerToken(), sessionId, ownerToken, lease)) {
                // 两张表的更新必须在当前事务中整体回滚，禁止只续租一侧。
                throw new IllegalStateException("UPLOAD_TAKEOVER_LOST");
            }
            return BlobReservation.ownedStaging(sessionId, ownerToken, blob.id(), blob.objectKey());
        }
        UploadSession target = sessions.findForUpdate(sessionId).orElse(null);
        StoredBlob blob = blobs.findByHashForUpdate(upload.sha256()).orElse(null);
        if (target == null || blob == null) return null;
        if (blob.status() == BlobStatus.DELETED) {
            String key = "blobs/" + UUID.randomUUID();
            if (blobs.restageDeleted(blob.id(), sessionId, ownerToken, key, lease)
                && sessions.bindBlob(sessionId, ownerToken, blob.id())) {
                return BlobReservation.ownedStaging(sessionId, ownerToken, blob.id(), key);
            }
        }
        return reservationFor(blob, sessionId, ownerToken);
    }

    @Override
    public BlobReservation resolve(UUID sessionId, UUID ownerToken, InspectedUpload upload) {
        if (configuredLease == null) throw new IllegalStateException("staging lease must be configured explicitly");
        return resolve(sessionId, ownerToken, upload, configuredLease);
    }

    /** 在一个数据库事务内完成 Blob 发布、逻辑文件、引用、会话和审计。 */
    public UploadResult finalizeUpload(UUID sessionId, UUID ownerToken, long blobId) {
        return finalizeUpload(sessionId, ownerToken, blobId, CorrelationId.random());
    }

    /** 使用已经领取的结果完成上传，供编排服务保持令牌和 Blob 一致。 */
    public UploadResult finalizeUpload(BlobReservation.Granted reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation is required");
        }
        return finalizeUpload(reservation.sessionId(), reservation.ownerToken(), reservation.blobId(), CorrelationId.random());
    }

    public UploadResult finalizeUpload(BlobReservation.Granted reservation, CorrelationId correlationId) {
        if (reservation == null || correlationId == null) throw new IllegalArgumentException("reservation and correlationId are required");
        return finalizeUpload(reservation.sessionId(), reservation.ownerToken(), reservation.blobId(), correlationId);
    }

    /** READY Blob 复用路径与普通终结共用同一原子事务。 */
    public UploadResult attachReadyBlob(BlobReservation.ReadyReuse reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must refer to a READY blob");
        }
        return finalizeUpload(reservation);
    }

    public UploadResult attachReadyBlob(BlobReservation.ReadyReuse reservation, CorrelationId correlationId) {
        if (reservation == null || correlationId == null) throw new IllegalArgumentException("reservation and correlationId are required");
        return finalizeUpload(reservation, correlationId);
    }

    /** 记录失败分类；条件更新确保迟到执行者不能覆盖接管者。 */
    @Override
    public void recordFailure(UUID sessionId, UUID ownerToken, String failureCode) {
        transactionTemplate.executeWithoutResult(status -> {
            if (!sessions.recordFailure(sessionId, ownerToken, failureCode)) {
                // 已完成、已接管或已失败的会话均无需再次覆盖状态。
            }
        });
    }

    /** 旧令牌或过期租约只能得到 false，不得覆盖新 owner 的状态。 */
    public boolean tryFinalize(UUID sessionId, UUID ownerToken, long blobId) {
        try {
            finalizeUpload(sessionId, ownerToken, blobId);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public UploadResult finalizeUpload(UUID sessionId, UUID ownerToken, long blobId, CorrelationId correlationId) {
        if (correlationId == null) throw new IllegalArgumentException("correlationId is required");
        UploadResult result = transactionTemplate.execute(status -> finalizeInTransaction(sessionId, ownerToken, blobId, correlationId));
        if (result == null) throw new IllegalStateException("upload finalization returned no result");
        return result;
    }

    private UploadResult finalizeInTransaction(UUID sessionId, UUID ownerToken, long blobId, CorrelationId correlationId) {
        UploadSession session = sessions.findForUpdate(sessionId)
            .orElseThrow(() -> new IllegalStateException("UPLOAD_SESSION_NOT_FOUND"));
        if (!ownerToken.equals(session.ownerToken())
            || (session.status() != com.example.files.domain.UploadSessionStatus.VALIDATED
                && session.status() != com.example.files.domain.UploadSessionStatus.FINALIZING)) {
            throw new IllegalStateException("UPLOAD_SESSION_NOT_OWNED");
        }
        if (session.blobId() == null || session.blobId() != blobId
            || session.contentHash() == null || session.actualSize() == null || session.detectedType() == null) {
            throw new IllegalStateException("UPLOAD_BLOB_NOT_BOUND");
        }
        if (session.status() == com.example.files.domain.UploadSessionStatus.VALIDATED
            && !sessions.markFinalizing(sessionId, ownerToken, blobId)) {
            throw new IllegalStateException("UPLOAD_SESSION_LEASE_LOST");
        }
        StoredBlob blob = blobs.findForUpdate(blobId)
            .orElseThrow(() -> new IllegalStateException("BLOB_NOT_FOUND"));
        if (!session.contentHash().equalsIgnoreCase(blob.contentHash())
            || session.actualSize() != blob.sizeBytes()
            || session.detectedType() != blob.mediaType()) {
            throw new IllegalStateException("UPLOAD_BLOB_METADATA_MISMATCH");
        }
        if (blob.status() == BlobStatus.STAGING) {
            if (!sessionId.equals(blob.stagingSessionId()) || !ownerToken.equals(blob.stagingOwnerToken())
                || !blobs.markReady(blobId, sessionId, ownerToken)) {
                throw new IllegalStateException("BLOB_LEASE_LOST");
            }
        } else if (blob.status() != BlobStatus.READY) {
            throw new IllegalStateException("BLOB_NOT_READY");
        }
        StoredFile file = files.create(session.uploaderId(), blobId, session.originalName());
        if (!blobs.incrementReference(blobId)) {
            throw new IllegalStateException("BLOB_REFERENCE_FAILED");
        }
        if (!sessions.markCompleted(sessionId, ownerToken, file.fileId())) {
            throw new IllegalStateException("UPLOAD_SESSION_COMPLETION_FAILED");
        }
        audits.record(new AuditEvent(correlationId, session.uploaderId(), AuditAction.UPLOAD_COMPLETED,
            file.fileId(), null, "SUCCESS", null, null, file.createdAt()));
        return new UploadResult(file.fileId(), file.displayName().value(), blob.mediaTypeValue(), blob.sizeBytes(), file.createdAt());
    }

    /** 在同一事务内接管过期会话和 Blob，确保新会话可以继续终结。 */
    public boolean takeOverExpiredStaging(long blobId, UUID oldSessionId, UUID oldOwnerToken,
                                          UUID newSessionId, UUID newOwnerToken, Duration lease) {
        Boolean result = transactionTemplate.execute(status -> {
            if (oldSessionId == null || newSessionId == null || oldSessionId.equals(newSessionId)) {
                throw new IllegalArgumentException("takeover requires two distinct sessions");
            }
            UUID firstSessionId = oldSessionId.toString().compareTo(newSessionId.toString()) < 0
                ? oldSessionId : newSessionId;
            UUID secondSessionId = firstSessionId.equals(oldSessionId) ? newSessionId : oldSessionId;
            UploadSession firstSession = sessions.findForUpdate(firstSessionId)
                .orElseThrow(() -> new IllegalStateException("UPLOAD_SESSION_NOT_FOUND"));
            UploadSession secondSession = sessions.findForUpdate(secondSessionId)
                .orElseThrow(() -> new IllegalStateException("UPLOAD_SESSION_NOT_FOUND"));
            UploadSession oldSession = oldSessionId.equals(firstSessionId) ? firstSession : secondSession;
            UploadSession newSession = newSessionId.equals(firstSessionId) ? firstSession : secondSession;
            StoredBlob blob = blobs.findForUpdate(blobId)
                .orElseThrow(() -> new IllegalStateException("BLOB_NOT_FOUND"));
            if (blob.status() != BlobStatus.STAGING || !oldSessionId.equals(blob.stagingSessionId())
                || !oldOwnerToken.equals(blob.stagingOwnerToken()) || newSession.contentHash() == null
                || !newSession.contentHash().equalsIgnoreCase(blob.contentHash())
                || newSession.actualSize() == null || newSession.actualSize() != blob.sizeBytes()
                || newSession.detectedType() != blob.mediaType()) {
                return false;
            }
            if (!sessions.takeOverExpired(oldSessionId, oldOwnerToken, newSessionId, newOwnerToken, blobId, lease)
                || !blobs.takeOverExpiredStaging(blobId, oldSessionId, oldOwnerToken,
                newSessionId, newOwnerToken, lease)) {
                throw new IllegalStateException("UPLOAD_TAKEOVER_LOST");
            }
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    /** 以数据库时间和 token fencing 原子领取恢复会话及其 Blob。 */
    public boolean claimRecovery(UploadSession candidate, UUID newOwnerToken, Duration lease) {
        if (candidate == null || newOwnerToken == null || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("invalid recovery claim arguments");
        }
        try {
            Boolean claimed = transactionTemplate.execute(status -> {
                UploadSession current = sessions.findForUpdate(candidate.sessionId()).orElse(null);
                if (current == null || !candidate.ownerToken().equals(current.ownerToken()) || current.blobId() == null) {
                    return false;
                }
                StoredBlob blob = blobs.findForUpdate(current.blobId()).orElse(null);
                if (blob == null) return false;
                if (!sessions.claimExpiredForRecovery(candidate.sessionId(), newOwnerToken, lease)) return false;
                if (blob.status() == BlobStatus.STAGING
                    && !blobs.takeOverExpiredStaging(blob.id(), candidate.sessionId(), candidate.ownerToken(),
                        candidate.sessionId(), newOwnerToken, lease)) {
                    throw new IllegalStateException("UPLOAD_RECOVERY_TAKEOVER_LOST");
                }
                return true;
            });
            return Boolean.TRUE.equals(claimed);
        } catch (IllegalStateException lost) {
            if ("UPLOAD_RECOVERY_TAKEOVER_LOST".equals(lost.getMessage())) return false;
            throw lost;
        }
    }

    /** 无正式对象或元数据不一致时，状态推进和清理任务必须同事务提交。 */
    public void markRecoveryPending(UploadSession session, StoredBlob blob, UUID ownerToken,
                                    String failureCode, CleanupTaskRepository cleanupTasks) {
        if (session == null || blob == null || ownerToken == null || failureCode == null || cleanupTasks == null) {
            throw new IllegalArgumentException("invalid recovery cleanup arguments");
        }
        transactionTemplate.executeWithoutResult(status -> {
            UploadSession lockedSession = sessions.findForUpdate(session.sessionId())
                .orElseThrow(() -> new IllegalStateException("UPLOAD_SESSION_NOT_FOUND"));
            StoredBlob lockedBlob = blobs.findForUpdate(blob.id())
                .orElseThrow(() -> new IllegalStateException("BLOB_NOT_FOUND"));
            boolean stagingOwned = lockedBlob.status() == BlobStatus.STAGING
                && session.sessionId().equals(lockedBlob.stagingSessionId())
                && ownerToken.equals(lockedBlob.stagingOwnerToken());
            boolean finalizingReady = lockedBlob.status() == BlobStatus.READY
                && lockedSession.status() == com.example.files.domain.UploadSessionStatus.FINALIZING
                && lockedSession.blobId() != null && lockedSession.blobId() == blob.id();
            if (!ownerToken.equals(lockedSession.ownerToken()) || (!stagingOwned && !finalizingReady)) {
                throw new IllegalStateException("UPLOAD_RECOVERY_FENCED");
            }
            if (!blobs.markPendingDeleteFromRecovery(blob.id(), session.sessionId(), ownerToken)) {
                throw new IllegalStateException("UPLOAD_RECOVERY_STATE_LOST");
            }
            StoredBlob pending = blobs.findForUpdate(blob.id()).orElseThrow();
            cleanupTasks.enqueueBlob(pending);
            cleanupTasks.enqueueTemp(lockedSession, lockedSession.tempKey());
            if (!sessions.recordFailure(session.sessionId(), ownerToken, failureCode)) {
                throw new IllegalStateException("UPLOAD_RECOVERY_SESSION_LOST");
            }
        });
    }

    /** 没有关联 Blob 的过期会话也必须同事务记录失败和临时清理。 */
    public void markRecoveryFailure(UploadSession session, UUID ownerToken,
                                     String failureCode, CleanupTaskRepository cleanupTasks) {
        if (session == null || ownerToken == null || failureCode == null || cleanupTasks == null) {
            throw new IllegalArgumentException("invalid recovery failure arguments");
        }
        transactionTemplate.executeWithoutResult(status -> {
            UploadSession locked = sessions.findForUpdate(session.sessionId())
                .orElseThrow(() -> new IllegalStateException("UPLOAD_SESSION_NOT_FOUND"));
            if (!ownerToken.equals(locked.ownerToken())
                || !sessions.recordFailure(session.sessionId(), ownerToken, failureCode)) {
                throw new IllegalStateException("UPLOAD_RECOVERY_FENCED");
            }
            cleanupTasks.enqueueTemp(locked, locked.tempKey());
        });
    }

}
