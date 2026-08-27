package com.example.files.application.access;

import java.util.UUID;
import java.util.Optional;

/** ACL 与逻辑删除持久化端口；实现必须在调用方事务中执行数据库操作。 */
public interface FileAccessRepository {
    /** 必须使用一条存在性和权限合并查询，且不可返回不可读文件元数据。 */
    AccessDecision findAccess(long actorId, UUID fileId);

    /**
     * Resolves the physical target only after the caller has committed its
     * authorization audit. Implementations must repeat the current ACL check.
     */
    default Optional<DownloadTarget> findDownloadTarget(long actorId, UUID fileId) {
        return Optional.empty();
    }

    /** 锁定文件行并检查所有者；实现可返回已删除所有者以支持删除幂等。 */
    default boolean isOwnerForUpdate(long actorId, UUID fileId) {
        AccessDecision decision = findAccess(actorId, fileId);
        return decision.view() != null && decision.view().ownerId() == actorId;
    }

    /** 授权和撤权只允许 ACTIVE owner；删除则另行允许已删除 owner 幂等重试。 */
    default boolean isActiveOwnerForUpdate(long actorId, UUID fileId) {
        return isOwnerForUpdate(actorId, fileId);
    }

    /** 插入授权；唯一键冲突必须视为成功（重复授权幂等）。 */
    default boolean grant(long actorId, UUID fileId, long granteeId) {
        throw new UnsupportedOperationException("grant is not supported");
    }

    /** 删除授权；不存在授权也视为成功（重复撤权幂等）。 */
    default boolean revoke(long actorId, UUID fileId, long granteeId) {
        throw new UnsupportedOperationException("revoke is not supported");
    }

    /** 以 file -> blob -> grants -> cleanup 锁序执行逻辑删除。 */
    default boolean delete(long actorId, UUID fileId) {
        throw new UnsupportedOperationException("delete is not supported");
    }

    /** Physical storage capability intentionally kept separate from FileView. */
    record DownloadTarget(FileView view, String objectKey) {
        public DownloadTarget {
            if (view == null || objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("invalid download target");
            }
        }
    }
}
