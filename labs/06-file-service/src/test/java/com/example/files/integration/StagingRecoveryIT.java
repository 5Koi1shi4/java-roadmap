package com.example.files.integration;

import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.StagingRecoveryService;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.SafeDisplayName;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import com.example.files.infrastructure.persistence.JdbcBlobRepository;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.persistence.JdbcFileRepository;
import com.example.files.infrastructure.persistence.JdbcUploadSessionRepository;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

    /** 真实 MySQL 下验证过期 STAGING 对象缺失时的 fencing 与补偿入队。 */
class StagingRecoveryIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static JdbcUploadSessionRepository sessions;
    private static JdbcBlobRepository blobs;
    private static UploadTransactionService transactions;
    private static JdbcCleanupTaskRepository cleanup;
    private static LocalObjectStorage storage;

    @BeforeAll
    static void prepare() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        sessions = new JdbcUploadSessionRepository(jdbc);
        blobs = new JdbcBlobRepository(jdbc);
        cleanup = new JdbcCleanupTaskRepository(jdbc);
        transactions = new UploadTransactionService(sessions, blobs, new JdbcFileRepository(jdbc),
            new JdbcAuditRecorder(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)), testProperties());
        storage = new LocalObjectStorage(Files.createTempDirectory("staging-recovery"));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM storage_cleanup_task");
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
    }

    @Test
    void missingFormalObjectIsReclaimedAndTempCleanupIsQueued() {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("missing.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "9".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var session = transactions.begin(9L, upload.originalName(), upload.declaredType(),
            Duration.ofHours(1), Duration.ofSeconds(1));
        var reservation = (com.example.files.application.upload.BlobReservation.Granted)
            transactions.reserve(session.sessionId(), session.ownerToken(), upload, Duration.ofSeconds(1));
        jdbc.update("UPDATE upload_session SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE session_id=?",
            session.sessionId().toString());
        jdbc.update("UPDATE stored_blob SET staging_lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
            reservation.blobId());

        StagingRecoveryService recovery = new StagingRecoveryService(sessions, blobs, transactions, storage, cleanup,
            Duration.ofSeconds(2));
        assertThat(recovery.recoverExpired(10, "recovery-worker")).isZero();
        assertThat(blobs.get(reservation.blobId()).status()).isEqualTo(com.example.files.domain.BlobStatus.PENDING_DELETE);
        assertThat(sessions.find(session.sessionId()).orElseThrow().status()).isEqualTo(com.example.files.domain.UploadSessionStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='BLOB_OBJECT'", Integer.class)).isOne();
    }

    @Test
    void blobStateAndCleanupTaskRollbackTogetherWhenEnqueueFails() {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("atomic.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "8".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var session = transactions.begin(10L, upload.originalName(), upload.declaredType(),
            Duration.ofHours(1), Duration.ofSeconds(1));
        var reservation = (com.example.files.application.upload.BlobReservation.Granted)
            transactions.reserve(session.sessionId(), session.ownerToken(), upload, Duration.ofSeconds(1));
        jdbc.update("UPDATE upload_session SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE session_id=?",
            session.sessionId().toString());
        jdbc.update("UPDATE stored_blob SET staging_lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
            reservation.blobId());
        CleanupTaskRepository failing = new CleanupTaskRepository() {
            @Override public void enqueueTemp(com.example.files.domain.UploadSession ignored, String key) { }
            @Override public void enqueueTempIfEligible(UUID ignored) { }
            @Override public void enqueueBlob(com.example.files.domain.StoredBlob ignored) {
                throw new IllegalStateException("injected cleanup database failure");
            }
            @Override public void enqueueBlob(com.example.files.application.upload.BlobReservation.Granted ignored) { }
        };
        StagingRecoveryService recovery = new StagingRecoveryService(sessions, blobs, transactions, storage, failing,
            Duration.ofSeconds(2));

        assertThatThrownBy(() -> recovery.recoverExpired(10, "recovery-worker"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("injected cleanup database failure");
        assertThat(blobs.get(reservation.blobId()).status()).isEqualTo(com.example.files.domain.BlobStatus.STAGING);
        assertThat(sessions.find(session.sessionId()).orElseThrow().status())
            .isEqualTo(com.example.files.domain.UploadSessionStatus.VALIDATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task", Integer.class)).isZero();
    }

    @Test
    void auditFailureAfterCommitIsRecoveredWhenFormalObjectMatches() {
        byte[] bytes = "pdf".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("audit.pdf"), "application/pdf",
            DetectedFileType.PDF, bytes.length, "7".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), bytes.length));
        var session = transactions.begin(11L, upload.originalName(), upload.declaredType(),
            Duration.ofHours(1), Duration.ofSeconds(1));
        storage.writeTemporary(session.tempKey(), new ByteArrayInputStream(bytes), 20_000_000L);
        var reservation = (com.example.files.application.upload.BlobReservation.Granted)
            transactions.reserve(session.sessionId(), session.ownerToken(), upload, Duration.ofSeconds(1));
        storage.commit(session.tempKey(), reservation.objectKey());
        AuditRecorder failingAudit = event -> { throw new IllegalStateException("injected audit failure"); };
        UploadTransactionService failing = new UploadTransactionService(sessions, blobs, new JdbcFileRepository(jdbc),
            failingAudit, new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()))), testProperties());
        assertThatThrownBy(() -> failing.finalizeUpload(reservation.sessionId(), reservation.ownerToken(), reservation.blobId()))
            .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE upload_session SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE session_id=?",
            session.sessionId().toString());
        jdbc.update("UPDATE stored_blob SET staging_lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
            reservation.blobId());

        StagingRecoveryService recovery = new StagingRecoveryService(sessions, blobs, transactions, storage, cleanup,
            Duration.ofSeconds(2));
        assertThat(recovery.recoverExpired(10, "recovery-worker")).isEqualTo(1);
        assertThat(blobs.get(reservation.blobId()).status()).isEqualTo(com.example.files.domain.BlobStatus.READY);
        assertThat(blobs.get(reservation.blobId()).referenceCount()).isOne();
        assertThat(sessions.find(session.sessionId()).orElseThrow().status())
            .isEqualTo(com.example.files.domain.UploadSessionStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='BLOB_OBJECT'", Integer.class)).isZero();
    }
}
