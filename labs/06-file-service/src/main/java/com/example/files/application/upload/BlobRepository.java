package com.example.files.application.upload;

import com.example.files.domain.StoredBlob;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** 物理 Blob 持久化端口，所有状态推进都带 owner fencing 条件。 */
public interface BlobRepository {
    BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease);

    Optional<StoredBlob> findByHash(String sha256);

    /** 在冲突事务结束后以短查询解析现有 Blob，不持有行锁。 */
    Optional<BlobReservation> resolveExisting(UUID sessionId, UUID ownerToken, InspectedUpload upload);

    Optional<StoredBlob> findByHashForUpdate(String sha256);

    default Optional<StoredBlob> findById(long blobId) {
        return Optional.empty();
    }

    default StoredBlob get(long blobId) {
        return findById(blobId).orElseThrow(() -> new IllegalArgumentException("blob not found"));
    }

    default Optional<StoredBlob> findForUpdate(long blobId) {
        return findById(blobId);
    }

    boolean markReady(long blobId, UUID sessionId, UUID ownerToken);

    /** 以 session + blob owner token 双重 fencing 延长 STAGING lease。 */
    boolean renewStagingLease(long blobId, UUID sessionId, UUID ownerToken, Duration lease);

    boolean takeOverExpiredStaging(long blobId, UUID sessionId, UUID newToken, Duration lease);

    default boolean takeOverExpiredStaging(long blobId, UUID oldSessionId, UUID oldOwnerToken,
                                           UUID newSessionId, UUID newToken, Duration lease) {
        throw new UnsupportedOperationException("atomic takeover is not supported");
    }

    default boolean incrementReference(long blobId) {
        throw new UnsupportedOperationException("incrementReference is not supported");
    }

    boolean restageDeleted(long blobId, UUID sessionId, UUID ownerToken, String objectKey, Duration lease);

    boolean markPendingDeleteFromRecovery(long blobId, UUID sessionId, UUID ownerToken);

    /** 清理任务 token/generation fencing：只允许当前代次进入 DELETING。 */
    default boolean claimDeletion(long blobId, long generation, UUID cleanupToken, Duration lease) {
        throw new UnsupportedOperationException("blob cleanup claiming is not supported");
    }

    /** 删除对象后以同一 token/generation 发布 DELETED。 */
    default boolean completeDeletion(long blobId, long generation, UUID cleanupToken) {
        throw new UnsupportedOperationException("blob cleanup completion is not supported");
    }
}
