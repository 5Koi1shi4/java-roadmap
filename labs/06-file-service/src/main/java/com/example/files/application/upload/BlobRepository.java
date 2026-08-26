package com.example.files.application.upload;

import com.example.files.domain.StoredBlob;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** 物理 Blob 持久化端口，所有状态推进都带 owner fencing 条件。 */
public interface BlobRepository {
    BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease);

    Optional<StoredBlob> findByHash(String sha256);

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

    boolean takeOverExpiredStaging(long blobId, UUID sessionId, UUID newToken, Duration lease);

    default boolean takeOverExpiredStaging(long blobId, UUID oldSessionId, UUID oldOwnerToken,
                                           UUID newSessionId, UUID newToken, Duration lease) {
        throw new UnsupportedOperationException("atomic takeover is not supported");
    }

    default boolean incrementReference(long blobId) {
        throw new UnsupportedOperationException("incrementReference is not supported");
    }

    /** 正式对象缺失时，在匹配 token 的条件下安全回收 STAGING 元数据。 */
    default boolean recoverMissingStaging(long blobId, UUID sessionId, UUID ownerToken) {
        return false;
    }

    /** 恢复任务为同一会话更换 owner token，旧 token 的迟到结果被拒绝。 */
    default boolean renewOwnershipForRecovery(long blobId, UUID sessionId, UUID oldOwnerToken,
                                              UUID newOwnerToken, Duration lease) {
        return false;
    }
}
