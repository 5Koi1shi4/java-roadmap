package com.example.files.application.upload;

import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSession;

import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** 上传会话持久化端口；租约时间由数据库时钟计算。 */
public interface UploadSessionRepository {
    UploadSession create(long uploaderId, String tempKey, UUID ownerToken,
                         SafeDisplayName name, String declaredType, Duration ttl, Duration lease);

    boolean markValidated(UUID sessionId, UUID ownerToken, InspectedUpload inspected);

    boolean markFinalizing(UUID sessionId, UUID ownerToken, long blobId);

    boolean bindBlob(UUID sessionId, UUID ownerToken, long blobId);

    boolean markCompleted(UUID sessionId, UUID ownerToken, UUID fileId);

    /** 以 owner token fencing 记录有限失败分类。 */
    default boolean recordFailure(UUID sessionId, UUID ownerToken, String failureCode) {
        return false;
    }

    Optional<UploadSession> find(UUID sessionId);

    boolean takeOverExpired(UUID oldSessionId, UUID oldOwnerToken, UUID newSessionId,
                            UUID newOwnerToken, long blobId, Duration lease);

    default Optional<UploadSession> findForUpdate(UUID sessionId) {
        return find(sessionId);
    }

    default List<UploadSession> findExpiredForRecovery(int batchSize) { return List.of(); }

    default boolean claimExpiredForRecovery(UUID sessionId, UUID newOwnerToken, Duration lease) { return false; }
}
