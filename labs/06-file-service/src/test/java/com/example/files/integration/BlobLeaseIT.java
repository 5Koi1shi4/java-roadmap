package com.example.files.integration;

import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.domain.BlobStatus;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSessionStatus;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用数据库时间和令牌 fencing 验证 Blob 租约接管。 */
class BlobLeaseIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static JdbcBlobRepository blobs;
    private static JdbcUploadSessionRepository sessions;
    private static UploadTransactionService transactions;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .load().migrate();
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        sessions = new JdbcUploadSessionRepository(jdbc);
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
        BlobReservation.Granted first = (BlobReservation.Granted) transactions.reserve(firstSession.sessionId(), firstSession.ownerToken(), upload,
            Duration.ofSeconds(1));
        jdbc.update("UPDATE stored_blob SET staging_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id=?",
            first.blobId());

        UUID secondSession = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        assertThat(blobs.takeOverExpiredStaging(first.blobId(), secondSession, secondToken, Duration.ofMinutes(2))).isTrue();

        assertThat(transactions.tryFinalize(first.sessionId(), first.ownerToken(), first.blobId())).isFalse();
        assertThat(blobs.markReady(first.blobId(), secondSession, secondToken)).isTrue();
    }

    @Test
    void newSessionTakeoverCanFinalizeAndOldOwnerIsFenced() {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("b.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "b".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var first = transactions.begin(31L, upload.originalName(), upload.declaredType(), Duration.ofHours(1), Duration.ofSeconds(1));
        BlobReservation.Granted reservation = (BlobReservation.Granted) transactions.reserve(first.sessionId(), first.ownerToken(), upload, Duration.ofSeconds(1));
        var second = transactions.begin(32L, upload.originalName(), upload.declaredType());
        assertThat(sessions.markValidated(second.sessionId(), second.ownerToken(), upload)).isTrue();
        jdbc.update("UPDATE upload_session SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE session_id=?",
            first.sessionId().toString());
        jdbc.update("UPDATE stored_blob SET staging_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id=?",
            reservation.blobId());

        assertThat(transactions.takeOverExpiredStaging(reservation.blobId(), first.sessionId(), first.ownerToken(),
            second.sessionId(), second.ownerToken(), Duration.ofMinutes(2))).isTrue();
        assertThat(transactions.tryFinalize(first.sessionId(), first.ownerToken(), reservation.blobId())).isFalse();
        var result = transactions.finalizeUpload(second.sessionId(), second.ownerToken(), reservation.blobId());

        assertThat(filesCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT reference_count FROM stored_blob WHERE id=?", Long.class, reservation.blobId())).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
            second.sessionId().toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event WHERE file_id=?", Integer.class,
            result.fileId().toString())).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
            first.sessionId().toString())).isEqualTo("EXPIRED");
    }

    @Test
    void crossedTakeoversUseDeterministicSessionLockOrderAndAtMostOneWins() throws Exception {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("cross.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "d".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var oldA = transactions.begin(51L, upload.originalName(), upload.declaredType(), Duration.ofHours(1), Duration.ofSeconds(1));
        BlobReservation.Granted reservation = (BlobReservation.Granted) transactions.reserve(oldA.sessionId(), oldA.ownerToken(), upload, Duration.ofSeconds(1));
        var oldB = transactions.begin(52L, upload.originalName(), upload.declaredType(), Duration.ofHours(1), Duration.ofSeconds(1));
        var newA = transactions.begin(53L, upload.originalName(), upload.declaredType());
        var newB = transactions.begin(54L, upload.originalName(), upload.declaredType());
        assertThat(sessions.markValidated(oldB.sessionId(), oldB.ownerToken(), upload)).isTrue();
        assertThat(sessions.markValidated(newA.sessionId(), newA.ownerToken(), upload)).isTrue();
        assertThat(sessions.markValidated(newB.sessionId(), newB.ownerToken(), upload)).isTrue();
        jdbc.update("UPDATE upload_session SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE session_id IN (?,?)",
            oldA.sessionId().toString(), oldB.sessionId().toString());
        jdbc.update("UPDATE stored_blob SET staging_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id=?",
            reservation.blobId());

        var firstOld = oldA.sessionId().toString().compareTo(oldB.sessionId().toString()) < 0 ? oldA : oldB;
        var secondOld = firstOld == oldA ? oldB : oldA;
        var firstNew = firstOld == oldA ? newB : newA;
        var secondNew = firstOld == oldA ? newA : newB;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> first = executor.submit(() -> {
                barrier.await();
                return transactions.takeOverExpiredStaging(reservation.blobId(), firstOld.sessionId(), firstOld.ownerToken(),
                    firstNew.sessionId(), firstNew.ownerToken(), Duration.ofMinutes(2));
            });
            Future<Boolean> second = executor.submit(() -> {
                barrier.await();
                return transactions.takeOverExpiredStaging(reservation.blobId(), secondOld.sessionId(), secondOld.ownerToken(),
                    secondNew.sessionId(), secondNew.ownerToken(), Duration.ofMinutes(2));
            });
            boolean firstResult = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean secondResult = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(firstResult ^ secondResult).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='STAGING'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE status='EXPIRED'", Integer.class)).isOne();
    }

    @Test
    void competingSessionReceivesWaitingReservationAndCannotFinalizeStagingBlob() {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("wait.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "e".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var owner = transactions.begin(61L, upload.originalName(), upload.declaredType());
        BlobReservation.Granted first = (BlobReservation.Granted) transactions.reserve(owner.sessionId(), owner.ownerToken(), upload);
        var competitor = transactions.begin(62L, upload.originalName(), upload.declaredType());
        BlobReservation waiting = transactions.reserve(competitor.sessionId(), competitor.ownerToken(), upload);

        assertThat(waiting.mode()).isEqualTo(BlobReservation.Mode.WAITING);
        assertThat(waiting).isInstanceOf(BlobReservation.Waiting.class);
        assertThat(waiting).isNotInstanceOf(BlobReservation.Granted.class);
        assertThat(blobs.get(first.blobId()).stagingSessionId()).isEqualTo(owner.sessionId());
        assertThat(blobs.get(first.blobId()).stagingOwnerToken()).isEqualTo(owner.ownerToken());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isZero();

        assertThat(blobs.markReady(first.blobId(), owner.sessionId(), owner.ownerToken())).isTrue();
        BlobReservation.ReadyReuse refreshed = (BlobReservation.ReadyReuse) transactions.resolve(
            competitor.sessionId(), competitor.ownerToken(), upload, Duration.ofMinutes(2));
        assertThat(refreshed.mode()).isEqualTo(BlobReservation.Mode.REUSE_READY);
        transactions.finalizeUpload(refreshed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isOne();
    }

    @Test
    void twoNewSessionsRaceForOneExpiredBlobAndOnlyOneCanTakeItOver() throws Exception {
        InspectedUpload upload = new InspectedUpload(SafeDisplayName.from("race.pdf"), "application/pdf",
            DetectedFileType.PDF, 3L, "f".repeat(64), new TemporaryObject("tmp/" + UUID.randomUUID(), 3L));
        var old = transactions.begin(71L, upload.originalName(), upload.declaredType(), Duration.ofHours(1), Duration.ofSeconds(1));
        BlobReservation.Granted reservation = (BlobReservation.Granted) transactions.reserve(old.sessionId(), old.ownerToken(), upload, Duration.ofSeconds(1));
        var newA = transactions.begin(72L, upload.originalName(), upload.declaredType());
        var newB = transactions.begin(73L, upload.originalName(), upload.declaredType());
        assertThat(sessions.markValidated(newA.sessionId(), newA.ownerToken(), upload)).isTrue();
        assertThat(sessions.markValidated(newB.sessionId(), newB.ownerToken(), upload)).isTrue();
        jdbc.update("UPDATE upload_session SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE session_id=?",
            old.sessionId().toString());
        jdbc.update("UPDATE stored_blob SET staging_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id=?",
            reservation.blobId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> first = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return transactions.takeOverExpiredStaging(reservation.blobId(), old.sessionId(), old.ownerToken(),
                    newA.sessionId(), newA.ownerToken(), Duration.ofMinutes(2));
            });
            Future<Boolean> second = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return transactions.takeOverExpiredStaging(reservation.blobId(), old.sessionId(), old.ownerToken(),
                    newB.sessionId(), newB.ownerToken(), Duration.ofMinutes(2));
            });
            boolean firstWon = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean secondWon = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(firstWon ^ secondWon).isTrue();
            var winner = firstWon ? newA : newB;
            var loser = firstWon ? newB : newA;
            assertThat(jdbc.queryForObject("SELECT staging_session_id FROM stored_blob WHERE id=?", String.class,
                reservation.blobId())).isEqualTo(winner.sessionId().toString());
            assertThat(jdbc.queryForObject("SELECT staging_owner_token FROM stored_blob WHERE id=?", String.class,
                reservation.blobId())).isEqualTo(winner.ownerToken().toString());
            assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
                old.sessionId().toString())).isEqualTo("EXPIRED");
            assertThat(sessions.find(winner.sessionId()).orElseThrow().status()).isEqualTo(UploadSessionStatus.VALIDATED);
            assertThat(sessions.find(winner.sessionId()).orElseThrow().blobId()).isEqualTo(reservation.blobId());
            assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
                loser.sessionId().toString())).isEqualTo("VALIDATED");
            assertThat(sessions.find(loser.sessionId()).orElseThrow().blobId()).isNull();
            assertThat(sessions.find(loser.sessionId()).orElseThrow().ownerToken()).isEqualTo(loser.ownerToken());
            transactions.finalizeUpload(winner.sessionId(), winner.ownerToken(), reservation.blobId());
            assertThat(sessions.find(winner.sessionId()).orElseThrow().status()).isEqualTo(UploadSessionStatus.COMPLETED);
            assertThat(blobs.get(reservation.blobId()).status()).isEqualTo(BlobStatus.READY);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isOne();
    }

    private int filesCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class);
    }
}
