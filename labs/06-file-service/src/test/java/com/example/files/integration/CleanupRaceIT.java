package com.example.files.integration;

import com.example.files.application.access.FileAccessService;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.cleanup.CleanupSummary;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.upload.*;
import com.example.files.infrastructure.persistence.*;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 MySQL 与本地对象存储验证最后引用删除和同哈希重传的代次 fencing。 */
class CleanupRaceIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static UploadService uploads;
    private static JdbcCleanupTaskRepository tasks;
    private static JdbcBlobRepository blobs;
    private static BlockingStorage storage;

    @BeforeAll static void prepare() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcUploadSessionRepository sessions = new JdbcUploadSessionRepository(jdbc);
        blobs = new JdbcBlobRepository(jdbc);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tasks = new JdbcCleanupTaskRepository(jdbc, tx);
        Path root = Files.createTempDirectory("cleanup-race");
        storage = new BlockingStorage(new LocalObjectStorage(root), root);
        UploadTransactionService transactions = new UploadTransactionService(sessions, blobs, new JdbcFileRepository(jdbc),
            new JdbcAuditRecorder(jdbc), tx, testProperties());
        uploads = new UploadService(transactions, new UploadInspector(), storage,
            new StagingWaitPolicy(Duration.ofSeconds(5), Duration.ofMillis(10)), tasks,
            new UploadFailureClassifier(), testProperties());
    }

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM storage_cleanup_task");
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
    }

    @Test void tenSameHashDeleteAndUploadRacesFenceOldGeneration() throws Exception {
        byte[] content = "%PDF-1.7 cleanup race".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long previousOldGeneration = 0L;
        UploadResult oldFile = uploads.upload(command(1000L, content));
        long oldOwner = 1000L;
        for (int round = 0; round < 10; round++) {
            final int iteration = round;
            long blobId = jdbc.queryForObject("SELECT blob_id FROM stored_file WHERE file_id=?", Long.class, oldFile.fileId().toString());
            String oldKey = jdbc.queryForObject("SELECT object_key FROM stored_blob WHERE id=?", String.class, blobId);
            long oldGeneration = jdbc.queryForObject("SELECT generation FROM stored_blob WHERE id=?", Long.class, blobId);
            assertThat(oldGeneration).isEqualTo(previousOldGeneration + 1L);
            String oldFileId = oldFile.fileId().toString();
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            new FileAccessService(new JdbcFileAccessRepository(jdbc), new JdbcAuditRecorder(jdbc), tx)
                .delete(oldOwner, oldFile.fileId(), CorrelationId.random());
            // 以同一真实 MySQL 事务明确制造最后引用归零的 PENDING_DELETE 状态。
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE stored_file SET status='DELETED',deleted_at=CURRENT_TIMESTAMP(6) WHERE file_id=? AND status='ACTIVE'", oldFileId);
                jdbc.update("UPDATE stored_blob SET reference_count=0,status='PENDING_DELETE',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", blobId);
                jdbc.update("DELETE FROM storage_cleanup_task WHERE task_type='BLOB_OBJECT' AND target_id=?", Long.toString(blobId));
                jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,status,available_at,attempt_count,created_at) "
                    + "SELECT ?, 'BLOB_OBJECT', CAST(id AS CHAR), generation, object_key, 'NEW', CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6) FROM stored_blob WHERE id=?",
                    UUID.randomUUID().toString(), blobId);
            });
            // 初次上传临时对象已完成；避免历史补偿任务抢占本轮专门的 Blob 竞争窗口。
            jdbc.update("DELETE FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT'");
            assertThat(jdbc.queryForObject("SELECT status FROM stored_blob WHERE id=?", String.class, blobId)).isEqualTo("PENDING_DELETE");

            storage.prepareOverlap(oldKey);
            StorageCleanupService cleaner = new StorageCleanupService(tasks, storage,
                new JdbcUploadSessionRepository(jdbc), blobs, testProperties());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<CleanupSummary> cleanup = executor.submit(() -> cleaner.runBatch("race-cleaner-" + iteration));
                assertThat(storage.deleteEntered.await(10, TimeUnit.SECONDS))
                    .as("cleaner must enter physical delete; task=%s blob=%s",
                        jdbc.queryForObject("SELECT status FROM storage_cleanup_task", String.class),
                        jdbc.queryForObject("SELECT status FROM stored_blob WHERE id=?", String.class, blobId))
                    .isTrue();
                storage.oldBlobToken = UUID.fromString(jdbc.queryForObject("SELECT cleanup_token FROM stored_blob WHERE id=?", String.class, blobId));
                storage.oldTaskId = UUID.fromString(jdbc.queryForObject("SELECT task_id FROM storage_cleanup_task WHERE target_id=? AND status='PROCESSING'", String.class, Long.toString(blobId)));
                storage.oldTaskToken = UUID.fromString(jdbc.queryForObject("SELECT claim_token FROM storage_cleanup_task WHERE task_id=?", String.class, storage.oldTaskId.toString()));
                Future<UploadResult> retried = executor.submit(() -> uploads.upload(command(2000L + iteration, content)));
                assertThat(storage.uploadStarted.await(10, TimeUnit.SECONDS)).isTrue();
                storage.releaseDelete.countDown();
                assertThat(cleanup.get(20, TimeUnit.SECONDS).completed()).isOne();
                UploadResult newFile = retried.get(20, TimeUnit.SECONDS);
                String newKey = jdbc.queryForObject("SELECT b.object_key FROM stored_file f JOIN stored_blob b ON b.id=f.blob_id WHERE f.file_id=?",
                    String.class, newFile.fileId().toString());
                assertThat(newKey).isNotEqualTo(oldKey);
                assertThat(storage.delegate.stat(newKey)).isNotNull();
                assertThatThrownBy(() -> storage.delegate.stat(oldKey)).isInstanceOf(RuntimeException.class);
                long newGeneration = jdbc.queryForObject("SELECT generation FROM stored_blob WHERE id=?", Long.class, blobId);
                assertThat(newGeneration).isEqualTo(oldGeneration + 1L);
                previousOldGeneration = oldGeneration;
                assertThat(jdbc.queryForObject("SELECT reference_count FROM stored_blob WHERE id=?", Long.class, blobId)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='READY'", Integer.class)).isOne();
                assertThatThrownBy(() -> tasks.completeBlobAndTask(blobId, oldGeneration, oldKey,
                    storage.oldBlobToken, storage.oldTaskId, storage.oldTaskToken)).isInstanceOf(IllegalStateException.class);
                oldFile = newFile;
                oldOwner = 2000L + iteration;
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static UploadCommand command(long actor, byte[] content) {
        return new UploadCommand(actor, "race.pdf", "application/pdf", content.length,
            new java.io.ByteArrayInputStream(content), CorrelationId.random());
    }

    private static final class BlockingStorage implements ObjectStorage {
        private final LocalObjectStorage delegate;
        private final Path root;
        private volatile String oldKey;
        private volatile CountDownLatch deleteEntered = new CountDownLatch(0);
        private volatile CountDownLatch uploadStarted = new CountDownLatch(0);
        private volatile CountDownLatch releaseDelete = new CountDownLatch(0);
        private volatile UUID oldBlobToken;
        private volatile UUID oldTaskId;
        private volatile UUID oldTaskToken;
        private BlockingStorage(LocalObjectStorage delegate, Path root) { this.delegate = delegate; this.root = root; }
        void prepareOverlap(String key) {
            oldKey = key;
            deleteEntered = new CountDownLatch(1);
            uploadStarted = new CountDownLatch(1);
            releaseDelete = new CountDownLatch(1);
            oldBlobToken = null;
            oldTaskId = null;
            oldTaskToken = null;
        }
        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            uploadStarted.countDown();
            return delegate.writeTemporary(key, source, maxBytes);
        }
        @Override public void commit(String tempKey, String objectKey) { delegate.commit(tempKey, objectKey); }
        @Override public InputStream open(String objectKey) { return delegate.open(objectKey); }
        @Override public StorageObjectMetadata stat(String objectKey) { return delegate.stat(objectKey); }
        @Override public void delete(String key) {
            if (!key.equals(oldKey)) { delegate.delete(key); return; }
            deleteEntered.countDown();
            try { if (!releaseDelete.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("overlap timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            delegate.delete(key);
        }
        @Override public Optional<URI> createPresignedGet(String key, Duration ttl, Map<String, String> headers) {
            return delegate.createPresignedGet(key, ttl, headers);
        }
    }
}
