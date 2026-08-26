package com.example.files.integration;

import com.example.files.application.audit.CorrelationId;
import com.example.files.application.upload.StagingWaitPolicy;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 下跨用户并发上传相同内容的物理去重验收。 */
class ConcurrentDeduplicationIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static UploadService service;

    @BeforeAll
    static void prepare() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        var sessions = new JdbcUploadSessionRepository(jdbc);
        var blobs = new JdbcBlobRepository(jdbc);
        var transactions = new UploadTransactionService(sessions, blobs, new JdbcFileRepository(jdbc),
            new JdbcAuditRecorder(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        service = new UploadService(transactions, new UploadInspector(),
            new LocalObjectStorage(Files.createTempDirectory("dedup")),
            new StagingWaitPolicy(Duration.ofSeconds(5), Duration.ofMillis(10)),
            new JdbcCleanupTaskRepository(jdbc), new UploadFailureClassifier());
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
    void concurrentSameContentCreatesOneBlobAndSeparateLogicalFiles() throws Exception {
        byte[] content = "%PDF-1.7 shared content for concurrent deduplication".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int round = 0; round < 3; round++) {
            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<UploadResult>> futures = new ArrayList<>();
                for (int user = 1; user <= 20; user++) {
                    long actor = user + round * 20L;
                    futures.add(executor.submit(() -> {
                        start.await();
                        return service.upload(new UploadCommand(actor, "shared.pdf", "application/pdf", content.length,
                            new java.io.ByteArrayInputStream(content), CorrelationId.random()));
                    }));
                }
                start.countDown();
                List<UploadResult> results = new ArrayList<>();
                for (Future<UploadResult> future : futures) results.add(future.get());
                assertThat(results).extracting(UploadResult::fileId).doesNotHaveDuplicates();
            } finally {
                executor.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='READY'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file WHERE status='ACTIVE'", Integer.class)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT reference_count FROM stored_blob WHERE status='READY'", Long.class)).isEqualTo(20L);
            jdbc.update("DELETE FROM file_audit_event");
            jdbc.update("DELETE FROM stored_file");
            jdbc.update("DELETE FROM stored_blob");
            jdbc.update("DELETE FROM upload_session");
        }
    }
}
