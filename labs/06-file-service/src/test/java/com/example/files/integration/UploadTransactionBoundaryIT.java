package com.example.files.integration;

import com.example.files.application.audit.CorrelationId;
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

import static org.assertj.core.api.Assertions.assertThat;

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
            new JdbcAuditRecorder(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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

    private static final class ProbeStorage implements ObjectStorage {
        private final ObjectStorage delegate;
        private boolean writeInTransaction;
        private boolean commitInTransaction;
        private boolean deleteInTransaction;
        private ProbeStorage(ObjectStorage delegate) { this.delegate = delegate; }
        private void reset() { writeInTransaction = false; commitInTransaction = false; deleteInTransaction = false; }
        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            writeInTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            return delegate.writeTemporary(key, source, maxBytes);
        }
        @Override public void commit(String tempKey, String objectKey) {
            commitInTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            delegate.commit(tempKey, objectKey);
        }
        @Override public InputStream open(String key) { return delegate.open(key); }
        @Override public void delete(String key) {
            deleteInTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            delegate.delete(key);
        }
        @Override public Optional<URI> createPresignedGet(String key, Duration ttl, Map<String, String> headers) {
            return delegate.createPresignedGet(key, ttl, headers);
        }
    }
}
