package com.example.files.application.upload;

import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
import com.example.files.domain.BlobStatus;
import com.example.files.domain.StoredBlob;
import com.example.files.domain.StoredFile;
import com.example.files.domain.UploadSession;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 编排上传会话、Blob 和逻辑文件的短事务；事务内不执行对象存储 IO。 */
public final class UploadTransactionService implements UploadService.Transactions {
    private final UploadSessionRepository sessions;
    private final BlobRepository blobs;
    private final FileRepository files;
    private final AuditRecorder audits;
    private final TransactionTemplate transactionTemplate;

    public UploadTransactionService(UploadSessionRepository sessions, BlobRepository blobs,
                                    FileRepository files, AuditRecorder audits,
                                    TransactionTemplate transactionTemplate) {
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.blobs = java.util.Objects.requireNonNull(blobs, "blobs");
        this.files = java.util.Objects.requireNonNull(files, "files");
        this.audits = java.util.Objects.requireNonNull(audits, "audits");
        this.transactionTemplate = java.util.Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /** 创建一小时默认会话；实际时间由数据库仓储读取。 */
    public UploadSession begin(long uploaderId, com.example.files.domain.SafeDisplayName name,
                               String declaredType) {
        return begin(uploaderId, name, declaredType, Duration.ofHours(1), Duration.ofMinutes(2));
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
                // 唯一键冲突事务已经结束；先在事务外短查询，再以新事务绑定会话。
                BlobReservation existing = blobs.resolveExisting(sessionId, ownerToken, upload).orElse(null);
                if (existing != null) {
                    return transactionTemplate.execute(status -> bindExistingInTransaction(sessionId, ownerToken, upload, existing));
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

    private BlobReservation bindExistingInTransaction(UUID sessionId, UUID ownerToken,
                                                      InspectedUpload upload, BlobReservation observed) {
        validateSession(sessionId, ownerToken, upload);
        BlobReservation current = blobs.resolveExisting(sessionId, ownerToken, upload).orElse(observed);
        bindReservation(sessionId, ownerToken, current);
        return current;
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
        return reserve(sessionId, ownerToken, upload, Duration.ofMinutes(2));
    }

    /** 以调用方最新校验结果重新解析领取状态，等待结果本身不作为后续能力使用。 */
    public BlobReservation resolve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease) {
        BlobReservation reservation = reserve(sessionId, ownerToken, upload, lease);
        if (!(reservation instanceof BlobReservation.Waiting)) return reservation;
        StoredBlob existing = blobs.findByHash(upload.sha256()).orElse(null);
        if (existing == null) return reservation;
        if (existing.status() == BlobStatus.STAGING && existing.stagingLeaseUntil() != null
            && !existing.stagingLeaseUntil().isAfter(Instant.now())
            && existing.stagingSessionId() != null && existing.stagingOwnerToken() != null
            && blobs.renewOwnershipForRecovery(existing.id(), existing.stagingSessionId(),
                existing.stagingOwnerToken(), ownerToken, lease)) {
            transactionTemplate.executeWithoutResult(status -> {
                if (!sessions.bindBlob(sessionId, ownerToken, existing.id()))
                    throw new IllegalStateException("UPLOAD_BLOB_BINDING_FAILED");
            });
            return BlobReservation.ownedStaging(sessionId, ownerToken, existing.id(), existing.objectKey());
        }
        if (existing.status() == BlobStatus.DELETED) {
            String key = "blobs/" + UUID.randomUUID();
            if (blobs.restageDeleted(existing.id(), sessionId, ownerToken, key, lease)) {
                transactionTemplate.executeWithoutResult(status -> {
                    if (!sessions.bindBlob(sessionId, ownerToken, existing.id()))
                        throw new IllegalStateException("UPLOAD_BLOB_BINDING_FAILED");
                });
                return BlobReservation.ownedStaging(sessionId, ownerToken, existing.id(), key);
            }
        }
        return reservation;
    }

    @Override
    public BlobReservation resolve(UUID sessionId, UUID ownerToken, InspectedUpload upload) {
        return resolve(sessionId, ownerToken, upload, Duration.ofMinutes(2));
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
        if (!ownerToken.equals(session.ownerToken()) || session.status() != com.example.files.domain.UploadSessionStatus.VALIDATED) {
            throw new IllegalStateException("UPLOAD_SESSION_NOT_OWNED");
        }
        if (session.blobId() == null || session.blobId() != blobId
            || session.contentHash() == null || session.actualSize() == null || session.detectedType() == null) {
            throw new IllegalStateException("UPLOAD_BLOB_NOT_BOUND");
        }
        if (!sessions.markFinalizing(sessionId, ownerToken, blobId)) {
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

    /** 恢复同一过期会话时，只更换 Blob owner token，保持会话 ID 不变。 */
    public boolean takeOverRecoveryOwnership(StoredBlob blob, UUID oldOwnerToken,
                                             UUID newOwnerToken, Duration lease) {
        if (blob == null || oldOwnerToken == null || newOwnerToken == null || lease == null) {
            throw new IllegalArgumentException("invalid recovery ownership arguments");
        }
        return blobs.renewOwnershipForRecovery(blob.id(), blob.stagingSessionId(), oldOwnerToken, newOwnerToken, lease);
    }

}
