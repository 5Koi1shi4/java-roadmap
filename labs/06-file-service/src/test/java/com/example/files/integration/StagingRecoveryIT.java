package com.example.files.integration;

import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.StagingRecoveryService;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadTransactionService;
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
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
            new JdbcAuditRecorder(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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
        assertThat(blobs.get(reservation.blobId()).status()).isEqualTo(com.example.files.domain.BlobStatus.DELETED);
        assertThat(sessions.find(session.sessionId()).orElseThrow().status()).isEqualTo(com.example.files.domain.UploadSessionStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT'", Integer.class)).isOne();
    }
}
