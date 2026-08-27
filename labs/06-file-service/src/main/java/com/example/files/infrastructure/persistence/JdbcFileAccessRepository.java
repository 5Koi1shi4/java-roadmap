package com.example.files.infrastructure.persistence;

import com.example.files.application.access.AccessDecision;
import com.example.files.application.access.FileAccessRepository;
import com.example.files.application.access.FileView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * ACL JDBC 实现。读取使用单条文件、Blob 与 grant 合并查询；删除严格按
 * stored_file -> stored_blob -> file_grant -> storage_cleanup_task 锁序完成。
 */
public final class JdbcFileAccessRepository implements FileAccessRepository {
    private final JdbcTemplate jdbc;

    public JdbcFileAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AccessDecision findAccess(long actorId, UUID fileId) {
        if (actorId <= 0 || fileId == null) throw new IllegalArgumentException("invalid access arguments");
        String sql = "SELECT f.file_id,f.owner_id,f.display_name,b.media_type,b.size_bytes,f.created_at,"
            + "CASE WHEN f.owner_id=? OR EXISTS (SELECT 1 FROM file_grant g "
            + "WHERE g.file_id=f.file_id AND g.grantee_user_id=?) THEN 1 ELSE 0 END AS readable "
            + "FROM stored_file f JOIN stored_blob b ON b.id=f.blob_id "
            + "WHERE f.file_id=? AND f.status='ACTIVE' AND b.status='READY'";
        try {
            AccessDecision result = jdbc.queryForObject(sql, (rs, row) -> mapDecision(rs),
                actorId, actorId, fileId.toString());
            return result == null ? AccessDecision.hidden() : result;
        } catch (EmptyResultDataAccessException e) {
            return AccessDecision.hidden();
        }
    }

    /** 锁定逻辑文件行并允许 owner 对已删除文件执行幂等删除。 */
    @Override
    public boolean isOwnerForUpdate(long actorId, UUID fileId) {
        if (actorId <= 0 || fileId == null) throw new IllegalArgumentException("invalid owner arguments");
        try {
            Long owner = jdbc.queryForObject("SELECT owner_id FROM stored_file WHERE file_id=? FOR UPDATE",
                Long.class, fileId.toString());
            return owner != null && owner == actorId;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    public boolean isActiveOwnerForUpdate(long actorId, UUID fileId) {
        if (actorId <= 0 || fileId == null) throw new IllegalArgumentException("invalid owner arguments");
        try {
            Long owner = jdbc.queryForObject("SELECT owner_id FROM stored_file WHERE file_id=? AND status='ACTIVE' FOR UPDATE",
                Long.class, fileId.toString());
            return owner != null && owner == actorId;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    public boolean grant(long actorId, UUID fileId, long granteeId) {
        if (actorId <= 0 || granteeId <= 0 || fileId == null) throw new IllegalArgumentException("invalid grant arguments");
        try {
            jdbc.update("INSERT INTO file_grant(file_id,grantee_user_id,granted_by,granted_at) "
                    + "VALUES(?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE grantee_user_id=VALUES(grantee_user_id)",
                fileId.toString(), granteeId, actorId);
            return true;
        } catch (DuplicateKeyException e) {
            // 兼容不支持 ON DUPLICATE KEY UPDATE 的驱动重试路径；唯一键仍保证幂等。
            return true;
        }
    }

    @Override
    public boolean revoke(long actorId, UUID fileId, long granteeId) {
        if (actorId <= 0 || granteeId <= 0 || fileId == null) throw new IllegalArgumentException("invalid revoke arguments");
        jdbc.update("DELETE FROM file_grant WHERE file_id=? AND grantee_user_id=?", fileId.toString(), granteeId);
        return true;
    }

    @Override
    public boolean delete(long actorId, UUID fileId) {
        if (actorId <= 0 || fileId == null) throw new IllegalArgumentException("invalid delete arguments");
        // 文件行已由服务层锁定；再次 SELECT 明确锁序并兼容直接调用实现。
        FileRow file = findFileForUpdate(fileId).orElse(null);
        if (file == null || file.ownerId != actorId) return false;
        if ("ACTIVE".equals(file.status)) {
            BlobRow blob = findBlobForUpdate(file.blobId)
                .orElseThrow(() -> new IllegalStateException("BLOB_NOT_FOUND"));
            if (!"READY".equals(blob.status) || blob.referenceCount <= 0) {
                throw new IllegalStateException("BLOB_REFERENCE_STATE_INVALID");
            }
            jdbc.update("UPDATE stored_file SET status='DELETED',deleted_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE file_id=? AND status='ACTIVE'", fileId.toString());
            // 授权删除发生在引用计数变化之前，且仍处于同一数据库事务。
            jdbc.update("DELETE FROM file_grant WHERE file_id=?", fileId.toString());
            int changed = jdbc.update("UPDATE stored_blob SET status=CASE WHEN reference_count=1 THEN 'PENDING_DELETE' ELSE status END,"
                    + "reference_count=reference_count-1,"
                    + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='READY' AND reference_count>0", file.blobId);
            if (changed != 1) throw new IllegalStateException("BLOB_REFERENCE_UPDATE_FAILED");
            if (blob.referenceCount == 1) enqueueBlobCleanup(blob.id, blob.generation, blob.objectKey);
            return true;
        }
        if ("DELETED".equals(file.status)) {
            // 已删除 owner 的重复请求只记录一次新的业务审计，不再次减少引用或入队。
            jdbc.update("DELETE FROM file_grant WHERE file_id=?", fileId.toString());
            return true;
        }
        throw new IllegalStateException("FILE_STATE_INVALID");
    }

    private void enqueueBlobCleanup(long blobId, long generation, String objectKey) {
        try {
            jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,"
                    + "status,available_at,attempt_count,created_at) VALUES(?,?,?,?,?,'NEW',CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "BLOB_OBJECT", Long.toString(blobId), generation, objectKey);
        } catch (DuplicateKeyException ignored) {
            // (task_type,target_id,target_generation) 唯一键提供并发/重复删除幂等。
        }
    }

    private Optional<FileRow> findFileForUpdate(UUID fileId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT owner_id,blob_id,status FROM stored_file WHERE file_id=? FOR UPDATE",
                (rs, row) -> new FileRow(rs.getLong(1), rs.getLong(2), rs.getString(3)), fileId.toString()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<BlobRow> findBlobForUpdate(long blobId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT id,generation,object_key,status,reference_count FROM stored_blob WHERE id=? FOR UPDATE",
                (rs, row) -> new BlobRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5)), blobId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private AccessDecision mapDecision(ResultSet rs) throws SQLException {
        FileView view = new FileView(UUID.fromString(rs.getString("file_id")), rs.getLong("owner_id"),
            rs.getString("display_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
            JdbcUploadSessionRepository.instant(rs, "created_at"));
        return AccessDecision.of(view, rs.getBoolean("readable"));
    }

    private record FileRow(long ownerId, long blobId, String status) { }
    private record BlobRow(long id, long generation, String objectKey, String status, long referenceCount) { }
}
