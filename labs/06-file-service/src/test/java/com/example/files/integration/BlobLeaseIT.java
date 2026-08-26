package com.example.files.integration;

import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.TemporaryObject;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用数据库时间和令牌 fencing 验证 Blob 租约接管。 */
class BlobLeaseIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static JdbcBlobRepository blobs;
    private static UploadTransactionService transactions;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .load().migrate();
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        var sessions = new JdbcUploadSessionRepository(jdbc);
        blobs = new JdbcBlobRepository(jdbc);
        transactions = new UploadTransactionService(sessions, blobs, new JdbcFileRepository(jdbc),
            new JdbcAuditRecorder(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @BeforeEach
    void cleanRows() {
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
    }

    @Test
    void lateOwnerCannotFinalizeAfterLeaseTakeover() {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("a.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "a".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var firstSession = transactions.begin(11L, upload.originalName(), upload.declaredType());
        BlobReservation first = transactions.reserve(firstSession.sessionId(), firstSession.ownerToken(), upload,
            Duration.ofSeconds(1));
        jdbc.update("UPDATE stored_blob SET staging_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id=?",
            first.blobId());

        UUID secondSession = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        assertThat(blobs.takeOverExpiredStaging(first.blobId(), secondSession, secondToken, Duration.ofMinutes(2))).isTrue();

        assertThat(transactions.tryFinalize(first.sessionId(), first.ownerToken(), first.blobId())).isFalse();
        assertThat(blobs.markReady(first.blobId(), secondSession, secondToken)).isTrue();
    }
}
