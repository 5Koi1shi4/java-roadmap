package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.upload.*;
import com.example.files.application.audit.CorrelationId;
import com.example.files.config.FileServiceProperties;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.storage.StorageUnavailableException;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同一组真实 MySQL/MinIO/Toxiproxy 容器连续三轮故障、恢复和双重终态演练。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"file.identity.trusted-header-enabled=true", "file.storage.type=minio",
        "file.cleanup.retry-delays=50ms", "file.cleanup.schedule=1h"})
@ActiveProfiles("test")
class StorageRecoveryDrillIT extends SharedStorageContainers {
    @Autowired private UploadService uploads;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcCleanupTaskRepository tasks;
    @Autowired private UploadSessionRepository sessions;
    @Autowired private BlobRepository blobs;
    @Autowired private ObjectStorage storage;
    @Autowired private FileServiceProperties properties;
    @Autowired private MinioObjectStorage minio;

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) { registerStorageProperties(registry); }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM storage_cleanup_task");
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM file_grant");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
    }

    @AfterEach
    void restoreProxy() { cutMinioConnection(false); }

    @Test
    void recoversThreeConsecutiveMinioOutagesWithoutDatabaseOrStorageOrphans() {
        byte[] bytes = "%PDF-1.7\nthree-round-drill".getBytes(StandardCharsets.US_ASCII);
        for (int round = 1; round <= 3; round++) {
            int currentRound = round;
            cutMinioConnection(true);
            assertThatThrownBy(() -> uploads.upload(new UploadCommand(700L + currentRound,
                "故障轮次-" + currentRound + ".pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes), CorrelationId.random())))
                .isInstanceOf(StorageUnavailableException.class);

            // 故障可能发生在临时对象写入或提交后的清理窗口；两种路径都必须留下可恢复会话，
            // 但不得留下 STAGING 孤儿。逻辑文件终态在恢复上传后按本轮 actor 单独核对。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE status IN ('FAILED','COMPLETED')", Integer.class))
                .isGreaterThanOrEqualTo(currentRound);

            cutMinioConnection(false);
            UploadResult recovered = uploads.upload(new UploadCommand(700L + round,
                "故障轮次-" + round + ".pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes), CorrelationId.random()));
            assertThat(recovered.fileId()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='STAGING'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='READY' AND reference_count=0", Integer.class)).isZero();
            jdbc.queryForList("SELECT object_key FROM stored_blob WHERE status='READY'", String.class)
                .forEach(key -> assertThat(minio.exists(key)).as("ready object %s", key).isTrue());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='READY' AND reference_count>0", Integer.class)).isGreaterThanOrEqualTo(1);

            StorageCleanupService cleaner = new StorageCleanupService(tasks, storage, sessions, blobs, properties);
            org.awaitility.Awaitility.await().untilAsserted(() -> {
                cleaner.runBatch("drill-" + currentRound + "-" + UUID.randomUUID());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('NEW','PROCESSING')", Integer.class)).isZero();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='STAGING'", Integer.class)).isZero();
        }
    }
}
