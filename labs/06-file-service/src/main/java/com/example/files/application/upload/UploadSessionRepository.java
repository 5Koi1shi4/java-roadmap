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

    /** 只延长当前 session 自身 lease；不会改变其绑定 Blob 的 owner。 */
    boolean renewLease(UUID sessionId, UUID ownerToken, Duration lease);

    boolean markCompleted(UUID sessionId, UUID ownerToken, UUID fileId);

    /** 以 owner token fencing 记录有限失败分类。 */
    boolean recordFailure(UUID sessionId, UUID ownerToken, String failureCode);

    Optional<UploadSession> find(UUID sessionId);

    boolean takeOverExpired(UUID oldSessionId, UUID oldOwnerToken, UUID newSessionId,
                            UUID newOwnerToken, long blobId, Duration lease);

    default Optional<UploadSession> findForUpdate(UUID sessionId) {
        return find(sessionId);
    }

    List<UploadSession> findExpiredForRecovery(int batchSize);

    boolean claimExpiredForRecovery(UUID sessionId, UUID newOwnerToken, Duration lease);

    /** 清理临时对象时以旧 owner 与 temp key fencing，并要求数据库 TTL 已到期。 */
    default boolean claimExpiredForRecovery(UUID sessionId, UUID expectedOwnerToken, String expectedTempKey,
                                            UUID newOwnerToken, Duration lease) {
        return claimExpiredForRecovery(sessionId, newOwnerToken, lease);
    }

    /** 使用数据库当前时间判断 TTL 是否已到期，不使用应用服务器时钟。 */
    default boolean isExpiredAtDatabaseTime(UUID sessionId) {
        throw new UnsupportedOperationException("database expiry probe is not supported");
    }
}
