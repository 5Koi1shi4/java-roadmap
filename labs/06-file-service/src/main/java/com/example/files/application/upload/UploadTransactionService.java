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
public final class UploadTransactionService {
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

    public UploadSession begin(long uploaderId, com.example.files.domain.SafeDisplayName name,
                               String declaredType, Duration ttl, Duration lease) {
        String tempKey = "tmp/" + UUID.randomUUID();
        UUID ownerToken = UUID.randomUUID();
        return sessions.create(uploaderId, tempKey, ownerToken, name, declaredType, ttl, lease);
    }

    /** 在验证阶段持久化真实大小、类型和哈希并领取物理 Blob。 */
    public BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease) {
        return transactionTemplate.execute(status -> {
            if (!sessions.markValidated(sessionId, ownerToken, upload)) {
                throw new IllegalStateException("UPLOAD_SESSION_NOT_VALIDATED");
            }
            BlobReservation reservation = blobs.reserve(sessionId, ownerToken, upload, lease);
            if (!sessions.bindBlob(sessionId, ownerToken, reservation.blobId())) {
                throw new IllegalStateException("UPLOAD_BLOB_BINDING_FAILED");
            }
            return reservation;
        });
    }

    public BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload) {
        return reserve(sessionId, ownerToken, upload, Duration.ofMinutes(2));
    }

    /** 在一个数据库事务内完成 Blob 发布、逻辑文件、引用、会话和审计。 */
    public UploadResult finalizeUpload(UUID sessionId, UUID ownerToken, long blobId) {
        UploadResult result = transactionTemplate.execute(status -> finalizeInTransaction(sessionId, ownerToken, blobId));
        if (result == null) throw new IllegalStateException("upload finalization returned no result");
        return result;
    }

    /** 使用已经领取的结果完成上传，供编排服务保持令牌和 Blob 一致。 */
    public UploadResult finalizeUpload(BlobReservation reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation must not be null");
        return finalizeUpload(reservation.sessionId(), reservation.ownerToken(), reservation.blobId());
    }

    /** READY Blob 复用路径与普通终结共用同一原子事务。 */
    public UploadResult attachReadyBlob(BlobReservation reservation) {
        if (reservation == null || !reservation.reusesReadyBlob()) {
            throw new IllegalArgumentException("reservation must refer to a READY blob");
        }
        return finalizeUpload(reservation);
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

    private UploadResult finalizeInTransaction(UUID sessionId, UUID ownerToken, long blobId) {
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
        audits.record(new AuditEvent(CorrelationId.random(), session.uploaderId(), AuditAction.UPLOAD_COMPLETED,
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

}
