package com.example.files.infrastructure.persistence;

import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.UploadSessionRepository;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSession;
import com.example.files.domain.UploadSessionStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** 基于 JdbcTemplate 的上传会话仓储；租约和时间比较全部使用 MySQL 时钟。 */
public final class JdbcUploadSessionRepository implements UploadSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcUploadSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public UploadSession create(long uploaderId, String tempKey, UUID ownerToken,
                                SafeDisplayName name, String declaredType, Duration ttl, Duration lease) {
        if (uploaderId <= 0 || tempKey == null || tempKey.isBlank() || ownerToken == null
            || name == null || ttl == null || lease == null || ttl.isNegative() || ttl.isZero()
            || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid upload session arguments");
        }
        UUID sessionId = UUID.randomUUID();
        long ttlMicros = micros(ttl);
        long leaseMicros = micros(lease);
        jdbc.update("INSERT INTO upload_session(session_id,uploader_id,temp_key,owner_token,status,"
                + "lease_until,expires_at,original_name,declared_type,created_at,updated_at) "
                + "VALUES(?,?,?,?,'RECEIVING',TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),"
                + "TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            sessionId.toString(), uploaderId, tempKey, ownerToken.toString(), leaseMicros, ttlMicros,
            name.value(), declaredType);
        return find(sessionId).orElseThrow(() -> new IllegalStateException("created upload session not found"));
    }

    @Override
    public boolean markValidated(UUID sessionId, UUID ownerToken, InspectedUpload inspected) {
        if (sessionId == null || ownerToken == null || inspected == null) {
            throw new IllegalArgumentException("invalid validation arguments");
        }
        return jdbc.update("UPDATE upload_session SET status='VALIDATED',actual_size=?,detected_type=?,"
                + "content_hash=?,updated_at=CURRENT_TIMESTAMP(6) WHERE session_id=? AND owner_token=? "
                + "AND status='RECEIVING' AND lease_until>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)",
            inspected.size(), inspected.mediaType(), inspected.sha256(), sessionId.toString(), ownerToken.toString()) == 1;
    }

    @Override
    public boolean markFinalizing(UUID sessionId, UUID ownerToken, long blobId) {
        if (sessionId == null || ownerToken == null || blobId <= 0) {
            throw new IllegalArgumentException("invalid finalization arguments");
        }
        return jdbc.update("UPDATE upload_session SET status='FINALIZING',updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE session_id=? AND owner_token=? AND status='VALIDATED' AND blob_id=? "
                + "AND lease_until>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)",
            sessionId.toString(), ownerToken.toString(), blobId) == 1;
    }

    @Override
    public boolean bindBlob(UUID sessionId, UUID ownerToken, long blobId) {
        if (sessionId == null || ownerToken == null || blobId <= 0) {
            throw new IllegalArgumentException("invalid blob binding arguments");
        }
        return jdbc.update("UPDATE upload_session SET blob_id=?,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE session_id=? AND owner_token=? AND status='VALIDATED' AND (blob_id IS NULL OR blob_id=?) "
                + "AND lease_until>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)",
            blobId, sessionId.toString(), ownerToken.toString(), blobId) == 1;
    }

    @Override
    public boolean takeOverExpired(UUID oldSessionId, UUID oldOwnerToken, UUID newSessionId,
                                   UUID newOwnerToken, long blobId, Duration lease) {
        if (oldSessionId == null || oldOwnerToken == null || newSessionId == null
            || newOwnerToken == null || blobId <= 0 || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid session takeover arguments");
        }
        int old = jdbc.update("UPDATE upload_session SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE session_id=? AND owner_token=? AND status IN ('RECEIVING','VALIDATED','FINALIZING') "
                + "AND lease_until<=CURRENT_TIMESTAMP(6)", oldSessionId.toString(), oldOwnerToken.toString());
        if (old != 1) return false;
        return jdbc.update("UPDATE upload_session SET owner_token=?,blob_id=?,"
                + "lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE session_id=? AND owner_token=? AND status='VALIDATED' AND blob_id IS NULL "
                + "AND lease_until>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)",
            newOwnerToken.toString(), blobId, micros(lease), newSessionId.toString(),
            newOwnerToken.toString()) == 1;
    }

    @Override
    public boolean markCompleted(UUID sessionId, UUID ownerToken, UUID fileId) {
        if (sessionId == null || ownerToken == null || fileId == null) {
            throw new IllegalArgumentException("invalid completion arguments");
        }
        return jdbc.update("UPDATE upload_session SET status='COMPLETED',file_id=?,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE session_id=? AND owner_token=? AND status='FINALIZING' "
                + "AND lease_until>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)",
            fileId.toString(), sessionId.toString(), ownerToken.toString()) == 1;
    }

    @Override
    public boolean recordFailure(UUID sessionId, UUID ownerToken, String failureCode) {
        if (sessionId == null || ownerToken == null || failureCode == null || failureCode.isBlank()
            || failureCode.length() > 64) throw new IllegalArgumentException("invalid failure arguments");
        return jdbc.update("UPDATE upload_session SET status='FAILED',failure_code=?,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE session_id=? AND owner_token=? AND status IN ('RECEIVING','VALIDATED','FINALIZING') "
                + "AND lease_until>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)",
            failureCode, sessionId.toString(), ownerToken.toString()) == 1;
    }

    @Override
    public Optional<UploadSession> find(UUID sessionId) {
        return findInternal(sessionId, false);
    }

    @Override
    public List<UploadSession> findExpiredForRecovery(int batchSize) {
        if (batchSize <= 0 || batchSize > 50) throw new IllegalArgumentException("invalid recovery batch size");
        return jdbc.query("SELECT session_id FROM upload_session WHERE status IN ('VALIDATED','FINALIZING') "
                + "AND lease_until<=CURRENT_TIMESTAMP(6) ORDER BY updated_at LIMIT ?",
            (rs, row) -> find(UUID.fromString(rs.getString(1))).orElseThrow(), batchSize);
    }

    @Override
    public boolean claimExpiredForRecovery(UUID sessionId, UUID newOwnerToken, Duration lease) {
        if (sessionId == null || newOwnerToken == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid recovery claim arguments");
        }
        return jdbc.update("UPDATE upload_session SET owner_token=?,lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),"
                + "expires_at=GREATEST(expires_at,TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))),"
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE session_id=? AND status IN ('VALIDATED','FINALIZING') "
                + "AND lease_until<=CURRENT_TIMESTAMP(6)",
            newOwnerToken.toString(), micros(lease), micros(lease), sessionId.toString()) == 1;
    }

    @Override
    public Optional<UploadSession> findForUpdate(UUID sessionId) {
        return findInternal(sessionId, true);
    }

    private Optional<UploadSession> findInternal(UUID sessionId, boolean lock) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        String sql = "SELECT session_id,uploader_id,temp_key,owner_token,status,lease_until,expires_at,"
            + "original_name,declared_type,actual_size,detected_type,content_hash,blob_id,file_id,"
            + "failure_code,created_at,updated_at FROM upload_session WHERE session_id=?"
            + (lock ? " FOR UPDATE" : "");
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, this::map, sessionId.toString()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private UploadSession map(ResultSet rs, int row) throws SQLException {
        return UploadSession.rehydrate(UUID.fromString(rs.getString("session_id")), rs.getLong("uploader_id"),
            rs.getString("temp_key"), UUID.fromString(rs.getString("owner_token")),
            UploadSessionStatus.valueOf(rs.getString("status")), instant(rs, "lease_until"),
            instant(rs, "expires_at"), SafeDisplayName.from(rs.getString("original_name")),
            rs.getString("declared_type"), nullableLong(rs, "actual_size"), type(rs.getString("detected_type")),
            rs.getString("content_hash"), nullableLong(rs, "blob_id"), nullableUuid(rs, "file_id"),
            rs.getString("failure_code"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    static DetectedFileType type(String value) {
        if (value == null) return null;
        for (DetectedFileType type : DetectedFileType.values()) {
            if (type.mediaType().equals(value)) return type;
        }
        throw new IllegalStateException("unsupported persisted media type");
    }

    static long micros(Duration duration) {
        try {
            long value = duration.toNanos() / 1_000L;
            if (value <= 0) throw new IllegalArgumentException("duration is below one microsecond");
            return value;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("duration is too large", e);
        }
    }
}
