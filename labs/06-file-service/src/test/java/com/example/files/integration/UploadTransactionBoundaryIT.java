package com.example.files.integration;

import com.example.files.application.audit.CorrelationId;
import com.example.files.application.upload.BlobRepository;
import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StagingWaitPolicy;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadCommand;
import com.example.files.application.upload.UploadFailureClassifier;
import com.example.files.application.upload.UploadInspector;
import com.example.files.application.upload.UploadResult;
import com.example.files.application.upload.UploadService;
import com.example.files.application.upload.UploadTransactionService;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 MySQL 与本地对象存储探针确认事务边界。 */
class UploadTransactionBoundaryIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static UploadService service;
    private static ProbeStorage storage;

    @BeforeAll
    static void prepare() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        var sessions = new JdbcUploadSessionRepository(jdbc);
        var blobs = new JdbcBlobRepository(jdbc);
        var transactions = new UploadTransactionService(sessions, blobs, new JdbcFileRepository(jdbc),
            new JdbcAuditRecorder(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)), testProperties());
        storage = new ProbeStorage(new LocalObjectStorage(Files.createTempDirectory("upload-boundary")));
        service = new UploadService(transactions, new UploadInspector(), storage,
            new StagingWaitPolicy(Duration.ofSeconds(2), Duration.ofMillis(5)),
            new JdbcCleanupTaskRepository(jdbc), new UploadFailureClassifier());
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM storage_cleanup_task");
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
        storage.reset();
    }

    @Test
    void neverRunsStorageIoInsideTransaction() {
        byte[] body = "%PDF-1.7 boundary".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        UploadResult result = service.upload(new UploadCommand(1L, "boundary.pdf", "application/pdf", body.length,
            new java.io.ByteArrayInputStream(body), CorrelationId.random()));
        assertThat(result.fileId()).isNotNull();
        assertThat(storage.writeInTransaction).isFalse();
        assertThat(storage.commitInTransaction).isFalse();
        assertThat(storage.deleteInTransaction).isFalse();
    }

    @Test
    void commitFailureLeavesDatabaseReservationRecoverable() {
        byte[] body = "%PDF-1.7 commit failure".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        storage.failCommit = true;
        assertThatThrownBy(() -> service.upload(new UploadCommand(2L, "commit.pdf", "application/pdf", body.length,
            new java.io.ByteArrayInputStream(body), CorrelationId.random())))
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE status='VALIDATED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='STAGING'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='BLOB_OBJECT'", Integer.class)).isZero();
    }

    @Test
    void transactionBFailureRecordsFailureWithoutLogicalOrPhysicalRows() {
        byte[] body = "%PDF-1.7 transaction B failure".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var failingTransactions = new UploadTransactionService(new JdbcUploadSessionRepository(jdbc),
            new FailingBlobRepository(), new JdbcFileRepository(jdbc), new JdbcAuditRecorder(jdbc),
            new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()))), testProperties());
        UploadService failingService = new UploadService(failingTransactions, new UploadInspector(), storage,
            new StagingWaitPolicy(Duration.ofSeconds(2), Duration.ofMillis(5)), new JdbcCleanupTaskRepository(jdbc),
            new UploadFailureClassifier());
        assertThatThrownBy(() -> failingService.upload(new UploadCommand(3L, "b-failure.pdf", "application/pdf", body.length,
            new java.io.ByteArrayInputStream(body), CorrelationId.random()))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE status='FAILED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT'", Integer.class)).isOne();
    }

    private static final class FailingBlobRepository implements BlobRepository {
        @Override public BlobReservation reserve(java.util.UUID sessionId, java.util.UUID ownerToken,
                                                   InspectedUpload upload, Duration lease) {
            throw new IllegalStateException("injected transaction B failure");
        }
        @Override public Optional<com.example.files.domain.StoredBlob> findByHash(String hash) { return Optional.empty(); }
        @Override public Optional<BlobReservation> resolveExisting(UUID sessionId, UUID ownerToken, InspectedUpload upload) { return Optional.empty(); }
        @Override public Optional<com.example.files.domain.StoredBlob> findByHashForUpdate(String hash) { return Optional.empty(); }
        @Override public boolean markReady(long blobId, UUID sessionId, UUID ownerToken) { return false; }
        @Override public boolean renewStagingLease(long blobId, UUID sessionId, UUID ownerToken, Duration lease) { return false; }
        @Override public boolean takeOverExpiredStaging(long blobId, UUID sessionId, UUID token, Duration lease) { return false; }
        @Override public boolean restageDeleted(long blobId, UUID sessionId, UUID token, String key, Duration lease) { return false; }
        @Override public boolean markPendingDeleteFromRecovery(long blobId, UUID sessionId, UUID token) { return false; }
    }

    private static final class ProbeStorage implements ObjectStorage {
        private final ObjectStorage delegate;
        private boolean writeInTransaction;
        private boolean commitInTransaction;
        private boolean deleteInTransaction;
        private boolean failCommit;
        private ProbeStorage(ObjectStorage delegate) { this.delegate = delegate; }
        private void reset() { writeInTransaction = false; commitInTransaction = false; deleteInTransaction = false; failCommit = false; }
        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            writeInTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            return delegate.writeTemporary(key, source, maxBytes);
        }
        @Override public void commit(String tempKey, String objectKey) {
            commitInTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            if (failCommit) throw new IllegalStateException("injected commit failure");
            delegate.commit(tempKey, objectKey);
        }
        @Override public InputStream open(String key) { return delegate.open(key); }
        @Override public com.example.files.application.upload.StorageObjectMetadata stat(String key) { return delegate.stat(key); }
        @Override public void delete(String key) {
            deleteInTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            delegate.delete(key);
        }
        @Override public Optional<URI> createPresignedGet(String key, Duration ttl, Map<String, String> headers) {
            return delegate.createPresignedGet(key, ttl, headers);
        }
    }
}
