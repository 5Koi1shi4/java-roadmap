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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.concurrent.Future;

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

    @Test
    void validSessionCannotFinalizeAgainstAnUnboundReadyBlob() {
        InspectedUpload firstUpload = upload("1".repeat(64));
        var firstSession = transactions.begin(21L, firstUpload.originalName(), firstUpload.declaredType());
        BlobReservation firstReservation = transactions.reserve(firstSession.sessionId(), firstSession.ownerToken(), firstUpload);
        transactions.finalizeUpload(firstReservation);

        InspectedUpload secondUpload = upload("2".repeat(64));
        var secondSession = transactions.begin(22L, secondUpload.originalName(), secondUpload.declaredType());
        BlobReservation secondReservation = transactions.reserve(secondSession.sessionId(), secondSession.ownerToken(), secondUpload);
        transactions.finalizeUpload(secondReservation);

        var attackerSession = transactions.begin(23L, firstUpload.originalName(), firstUpload.declaredType());
        BlobReservation bound = transactions.reserve(attackerSession.sessionId(), attackerSession.ownerToken(), firstUpload);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            transactions.finalizeUpload(attackerSession.sessionId(), attackerSession.ownerToken(), secondReservation.blobId()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
            attackerSession.sessionId().toString())).isEqualTo("VALIDATED");
        assertThat(blobs.get(firstReservation.blobId()).referenceCount()).isOne();
        assertThat(blobs.get(secondReservation.blobId()).referenceCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentIndependentReservationsConvergeOnOneBlobAndReadyReuseIncrementsReference() throws Exception {
        InspectedUpload upload = upload("3".repeat(64));
        var first = transactions.begin(41L, upload.originalName(), upload.declaredType());
        var second = transactions.begin(42L, upload.originalName(), upload.declaredType());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<BlobReservation>> futures = executor.invokeAll(List.of(
                () -> transactions.reserve(first.sessionId(), first.ownerToken(), upload),
                () -> transactions.reserve(second.sessionId(), second.ownerToken(), upload)));
            List<BlobReservation> reservations = new ArrayList<>();
            for (Future<BlobReservation> future : futures) {
                reservations.add(future.get());
            }
            assertThat(reservations).extracting(BlobReservation::blobId).containsOnly(reservations.get(0).blobId());
            BlobReservation owner = reservations.stream().filter(r -> r.mode() == BlobReservation.Mode.NEW_STAGING)
                .findFirst().orElseThrow();
            BlobReservation waiter = reservations.stream().filter(r -> r != owner).findFirst().orElseThrow();
            transactions.finalizeUpload(owner);
            transactions.finalizeUpload(waiter);
            assertThat(blobs.get(reservations.get(0).blobId()).referenceCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private static InspectedUpload upload(String hash) {
        return new InspectedUpload(SafeDisplayName.from("资料.pdf"), "application/pdf", DetectedFileType.PDF,
            3L, hash,
            new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
    }
}
