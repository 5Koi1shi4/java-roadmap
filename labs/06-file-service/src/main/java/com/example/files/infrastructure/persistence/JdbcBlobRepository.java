package com.example.files.infrastructure.persistence;

import com.example.files.application.upload.BlobRepository;
import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.domain.BlobStatus;
import com.example.files.domain.StoredBlob;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Blob JDBC 仓储；唯一哈希竞争会重新读取既有行。 */
public final class JdbcBlobRepository implements BlobRepository {
    private final JdbcTemplate jdbc;

    public JdbcBlobRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease) {
        if (sessionId == null || ownerToken == null || upload == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid blob reservation arguments");
        }
        String objectKey = "blobs/" + UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO stored_blob(content_hash,object_key,size_bytes,media_type,reference_count,status,"
                    + "generation,staging_session_id,staging_owner_token,staging_lease_until,created_at,updated_at) "
                    + "VALUES(?,?,?,?,0,'STAGING',1,?,?,TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                upload.sha256(), objectKey, upload.size(), upload.mediaType(), sessionId.toString(),
                ownerToken.toString(), JdbcUploadSessionRepository.micros(lease));
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            return BlobReservation.newStaging(sessionId, ownerToken, id, objectKey);
        } catch (DuplicateKeyException duplicate) {
            if (!isContentHashConflict(duplicate)) {
                throw duplicate;
            }
            StoredBlob winner = findByHashForUpdate(upload.sha256())
                .orElseThrow(() -> new IllegalStateException("blob hash race lost without winner"));
            return reservationFor(winner, sessionId, ownerToken);
        }
    }

    private boolean isContentHashConflict(DuplicateKeyException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("uk_blob_content_hash");
    }

    private BlobReservation reservationFor(StoredBlob blob, UUID sessionId, UUID ownerToken) {
        if (blob.status() == BlobStatus.READY) {
            return BlobReservation.readyReuse(sessionId, ownerToken, blob.id(), blob.objectKey());
        }
        if (blob.status() == BlobStatus.STAGING && sessionId.equals(blob.stagingSessionId())
            && ownerToken.equals(blob.stagingOwnerToken())) {
            return BlobReservation.ownedStaging(sessionId, ownerToken, blob.id(), blob.objectKey());
        }
        if (blob.status() == BlobStatus.STAGING) {
            return BlobReservation.waiting();
        }
        throw new IllegalStateException("BLOB_NOT_READY");
    }

    @Override
    public Optional<StoredBlob> findByHash(String sha256) {
        return findByHashInternal(sha256, false);
    }

    private Optional<StoredBlob> findByHashForUpdate(String sha256) {
        return findByHashInternal(sha256, true);
    }

    private Optional<StoredBlob> findByHashInternal(String sha256, boolean lock) {
        if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("sha256 must not be blank");
        String sql = "SELECT id,content_hash,object_key,size_bytes,media_type,reference_count,status,generation,"
            + "staging_session_id,staging_owner_token,staging_lease_until,cleanup_token,cleanup_lease_until,"
            + "created_at,updated_at FROM stored_blob WHERE content_hash=?" + (lock ? " FOR UPDATE" : "");
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, this::map, sha256));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<StoredBlob> findById(long blobId) {
        return findIdInternal(blobId, false);
    }

    @Override
    public Optional<StoredBlob> findForUpdate(long blobId) {
        return findIdInternal(blobId, true);
    }

    @Override
    public StoredBlob get(long blobId) {
        return findById(blobId).orElseThrow(() -> new IllegalArgumentException("blob not found"));
    }

    private Optional<StoredBlob> findIdInternal(long blobId, boolean lock) {
        if (blobId <= 0) throw new IllegalArgumentException("blobId must be positive");
        String sql = "SELECT id,content_hash,object_key,size_bytes,media_type,reference_count,status,generation,"
            + "staging_session_id,staging_owner_token,staging_lease_until,cleanup_token,cleanup_lease_until,"
            + "created_at,updated_at FROM stored_blob WHERE id=?" + (lock ? " FOR UPDATE" : "");
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, this::map, blobId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean markReady(long blobId, UUID sessionId, UUID ownerToken) {
        if (blobId <= 0 || sessionId == null || ownerToken == null) throw new IllegalArgumentException("invalid ready arguments");
        return jdbc.update("UPDATE stored_blob SET status='READY',staging_session_id=NULL,staging_owner_token=NULL,"
                + "staging_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='STAGING' "
                + "AND staging_session_id=? AND staging_owner_token=? AND staging_lease_until>CURRENT_TIMESTAMP(6)",
            blobId, sessionId.toString(), ownerToken.toString()) == 1;
    }

    @Override
    public boolean takeOverExpiredStaging(long blobId, UUID sessionId, UUID newToken, Duration lease) {
        if (blobId <= 0 || sessionId == null || newToken == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid takeover arguments");
        }
        return jdbc.update("UPDATE stored_blob SET staging_session_id=?,staging_owner_token=?,"
                + "staging_lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND status='STAGING' AND staging_session_id IS NOT NULL "
                + "AND staging_owner_token IS NOT NULL AND staging_lease_until<=CURRENT_TIMESTAMP(6)",
            sessionId.toString(), newToken.toString(), JdbcUploadSessionRepository.micros(lease), blobId) == 1;
    }

    @Override
    public boolean takeOverExpiredStaging(long blobId, UUID oldSessionId, UUID oldOwnerToken,
                                         UUID newSessionId, UUID newToken, Duration lease) {
        if (blobId <= 0 || oldSessionId == null || oldOwnerToken == null || newSessionId == null
            || newToken == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid atomic takeover arguments");
        }
        return jdbc.update("UPDATE stored_blob SET staging_session_id=?,staging_owner_token=?,"
                + "staging_lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND status='STAGING' AND staging_session_id=? AND staging_owner_token=? "
                + "AND staging_lease_until<=CURRENT_TIMESTAMP(6)",
            newSessionId.toString(), newToken.toString(), JdbcUploadSessionRepository.micros(lease), blobId,
            oldSessionId.toString(), oldOwnerToken.toString()) == 1;
    }

    @Override
    public boolean incrementReference(long blobId) {
        return jdbc.update("UPDATE stored_blob SET reference_count=reference_count+1,updated_at=CURRENT_TIMESTAMP(6) "
            + "WHERE id=? AND status='READY'", blobId) == 1;
    }

    @Override
    public boolean recoverMissingStaging(long blobId, UUID sessionId, UUID ownerToken) {
        if (blobId <= 0 || sessionId == null || ownerToken == null) {
            throw new IllegalArgumentException("invalid missing blob recovery arguments");
        }
        return jdbc.update("UPDATE stored_blob SET status='DELETED',staging_session_id=NULL,staging_owner_token=NULL,"
                + "staging_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='STAGING' "
                + "AND staging_session_id=? AND staging_owner_token=? AND reference_count=0",
            blobId, sessionId.toString(), ownerToken.toString()) == 1;
    }

    @Override
    public boolean renewOwnershipForRecovery(long blobId, UUID sessionId, UUID oldOwnerToken,
                                             UUID newOwnerToken, Duration lease) {
        if (blobId <= 0 || sessionId == null || oldOwnerToken == null || newOwnerToken == null
            || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("invalid recovery ownership arguments");
        }
        return jdbc.update("UPDATE stored_blob SET staging_owner_token=?,staging_lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),"
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='STAGING' AND staging_session_id=? "
                + "AND staging_owner_token=? AND staging_lease_until<=CURRENT_TIMESTAMP(6)",
            newOwnerToken.toString(), JdbcUploadSessionRepository.micros(lease), blobId,
            sessionId.toString(), oldOwnerToken.toString()) == 1;
    }

    private StoredBlob map(ResultSet rs, int row) throws SQLException {
        BlobStatus status = BlobStatus.valueOf(rs.getString("status"));
        UUID session = JdbcUploadSessionRepository.nullableUuid(rs, "staging_session_id");
        UUID owner = JdbcUploadSessionRepository.nullableUuid(rs, "staging_owner_token");
        Instant lease = JdbcUploadSessionRepository.instant(rs, "staging_lease_until");
        Instant now = JdbcUploadSessionRepository.instant(rs, "updated_at");
        return StoredBlob.rehydrate(rs.getLong("id"), rs.getString("content_hash"), rs.getString("object_key"),
            rs.getLong("size_bytes"), JdbcUploadSessionRepository.type(rs.getString("media_type")),
            rs.getLong("reference_count"), status, rs.getLong("generation"), session, owner, lease,
            JdbcUploadSessionRepository.nullableUuid(rs, "cleanup_token"),
            JdbcUploadSessionRepository.instant(rs, "cleanup_lease_until"),
            JdbcUploadSessionRepository.instant(rs, "created_at"), now);
    }
}
