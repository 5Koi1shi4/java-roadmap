package com.example.files.application.upload;

import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSession;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** 上传会话持久化端口；租约时间由数据库时钟计算。 */
public interface UploadSessionRepository {
    UploadSession create(long uploaderId, String tempKey, UUID ownerToken,
                         SafeDisplayName name, String declaredType, Duration ttl, Duration lease);

    boolean markValidated(UUID sessionId, UUID ownerToken, InspectedUpload inspected);

    boolean markFinalizing(UUID sessionId, UUID ownerToken, long blobId);

    boolean markCompleted(UUID sessionId, UUID ownerToken, UUID fileId);

    Optional<UploadSession> find(UUID sessionId);

    default Optional<UploadSession> findForUpdate(UUID sessionId) {
        return find(sessionId);
    }
}
