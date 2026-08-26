package com.example.files.integration;

import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadResult;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.SafeDisplayName;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import com.example.files.infrastructure.persistence.JdbcBlobRepository;
import com.example.files.infrastructure.persistence.JdbcFileRepository;
import com.example.files.infrastructure.persistence.JdbcUploadSessionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 MySQL 验证上传持久化和事务原子性。 */
class UploadPersistenceIT extends SharedMySqlContainer {

    private static JdbcTemplate jdbc;
    private static UploadTransactionService transactions;
    private static JdbcUploadSessionRepository sessions;
    private static TransactionTemplate transactionTemplate;
    private static JdbcBlobRepository blobs;
    private static JdbcFileRepository files;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .load().migrate();
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        sessions = new JdbcUploadSessionRepository(jdbc);
        blobs = new JdbcBlobRepository(jdbc);
        files = new JdbcFileRepository(jdbc);
        transactionTemplate = new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        transactions = new UploadTransactionService(sessions, blobs, files,
            new JdbcAuditRecorder(jdbc), transactionTemplate);
    }

    @BeforeEach
    void cleanRows() {
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
    }

    @Test
    void finalizesBlobFileReferenceAndAuditAtomically() {
        InspectedUpload upload = upload("1".repeat(64));
        var session = transactions.begin(7L, upload.originalName(), upload.declaredType());
        BlobReservation reservation = transactions.reserve(session.sessionId(), session.ownerToken(), upload,
            Duration.ofMinutes(2));

        UploadResult result = transactions.finalizeUpload(reservation.sessionId(), reservation.ownerToken(), reservation.blobId());

        assertThat(files.findActive(result.fileId())).isPresent();
        assertThat(blobs.get(reservation.blobId()).referenceCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event WHERE file_id=? AND action='UPLOAD_COMPLETED'",
            Integer.class, result.fileId().toString())).isOne();
    }

    @Test
    void rollbackLeavesNoFileReferenceOrAudit() {
        InspectedUpload upload = upload("2".repeat(64));
        var session = transactions.begin(8L, upload.originalName(), upload.declaredType());
        BlobReservation reservation = transactions.reserve(session.sessionId(), session.ownerToken(), upload,
            Duration.ofMinutes(2));
        AuditRecorder failingAudit = event -> { throw new RuntimeException("audit unavailable"); };
        UploadTransactionService failingTransactions = new UploadTransactionService(sessions, blobs, files,
            failingAudit, transactionTemplate);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            failingTransactions.finalizeUpload(reservation.sessionId(), reservation.ownerToken(), reservation.blobId()))
            .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isZero();
        assertThat(blobs.get(reservation.blobId()).referenceCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event", Integer.class)).isZero();
    }

    private static InspectedUpload upload(String hash) {
        return new InspectedUpload(SafeDisplayName.from("资料.pdf"), "application/pdf", DetectedFileType.PDF,
            3L, hash,
            new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
    }
}
