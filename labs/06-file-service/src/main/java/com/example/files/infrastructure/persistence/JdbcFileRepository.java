package com.example.files.infrastructure.persistence;

import com.example.files.application.upload.FileRepository;
import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.StoredFile;
import com.example.files.domain.StoredFileStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 逻辑文件 JDBC 仓储，文件 ID 使用随机 UUID 且不暴露 Blob 标识。 */
public final class JdbcFileRepository implements FileRepository {
    private final JdbcTemplate jdbc;

    public JdbcFileRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public StoredFile create(long ownerId, long blobId, SafeDisplayName displayName) {
        Instant databaseNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",
            (rs, row) -> rs.getTimestamp(1).toInstant());
        return create(ownerId, blobId, displayName, databaseNow);
    }

    @Override
    public StoredFile create(long ownerId, long blobId, SafeDisplayName displayName, Instant databaseNow) {
        if (ownerId <= 0 || blobId <= 0 || displayName == null || databaseNow == null) {
            throw new IllegalArgumentException("invalid stored file arguments");
        }
        UUID fileId = UUID.randomUUID();
        jdbc.update("INSERT INTO stored_file(file_id,owner_id,blob_id,display_name,status,created_at) "
                + "VALUES(?,?,?,?, 'ACTIVE', ?)", fileId.toString(), ownerId, blobId, displayName.value(), databaseNow);
        return new StoredFile(fileId, ownerId, blobId, displayName, StoredFileStatus.ACTIVE, databaseNow, null);
    }

    @Override
    public Optional<StoredFile> findActive(UUID fileId) {
        if (fileId == null) throw new IllegalArgumentException("fileId must not be null");
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT file_id,owner_id,blob_id,display_name,status,created_at,deleted_at "
                + "FROM stored_file WHERE file_id=? AND status='ACTIVE'", this::map, fileId.toString()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private StoredFile map(ResultSet rs, int row) throws SQLException {
        return new StoredFile(UUID.fromString(rs.getString("file_id")), rs.getLong("owner_id"), rs.getLong("blob_id"),
            SafeDisplayName.from(rs.getString("display_name")), StoredFileStatus.valueOf(rs.getString("status")),
            JdbcUploadSessionRepository.instant(rs, "created_at"), JdbcUploadSessionRepository.instant(rs, "deleted_at"));
    }
}
