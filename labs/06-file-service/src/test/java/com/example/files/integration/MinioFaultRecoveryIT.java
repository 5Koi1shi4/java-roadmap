package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.upload.UploadCommand;
import com.example.files.application.upload.UploadResult;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.infrastructure.storage.StorageFailureClassifier;
import com.example.files.infrastructure.storage.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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

/** 通过代理切断真实连接，验证可重试故障与提交恢复。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"file.storage.type=minio", "file.identity.trusted-header-enabled=true"})
@ActiveProfiles("test")
class MinioFaultRecoveryIT extends SharedStorageContainers {
    private final MinioObjectStorage storage = new MinioObjectStorage(minioClient(), minioStorage());
    @Autowired private com.example.files.application.upload.UploadService uploadService;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void registerFaultProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("file.storage.minio-endpoint", SharedStorageContainers::minioEndpoint);
        registry.add("file.storage.minio-access-key", () -> ACCESS_KEY);
        registry.add("file.storage.minio-secret-key", () -> SECRET_KEY);
        registry.add("file.storage.minio-bucket", () -> BUCKET);
        registry.add("file.storage.minio-region", () -> "us-east-1");
    }

    MinioFaultRecoveryIT() { storage.initialize(); }

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
    void commitConnectionCutIsRetryableAndRecoveryLeavesReadyObjectWithoutTempOrphan() throws Exception {
        String temp = "tmp/" + UUID.randomUUID();
        String object = "blobs/" + UUID.randomUUID();
        byte[] bytes = "recovery".getBytes(StandardCharsets.US_ASCII);
        storage.writeTemporary(temp, new ByteArrayInputStream(bytes), 1024);
        cutMinioConnection(true);
        assertThatThrownBy(() -> storage.commit(temp, object))
            .isInstanceOfSatisfying(StorageUnavailableException.class, unavailable -> {
                assertThat(unavailable.retryable()).isTrue();
                assertThat(unavailable.failureClass()).isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
            });
        cutMinioConnection(false);
        storage.commit(temp, object);
        assertThat(storage.stat(object)).isEqualTo(new StorageObjectMetadata(bytes.length));
        assertThat(storage.exists(temp)).isFalse();
        assertThat(storage.open(object).readAllBytes()).containsExactly(bytes);
    }

    @Test
    void connectionCutDuringRealUploadLeavesNoActiveRowsAndRecoveredUploadIsReady() {
        byte[] bytes = "%PDF-1.7\ntemporary-retry".getBytes(StandardCharsets.US_ASCII);
        cutMinioConnection(true);
        assertThatThrownBy(() -> uploadService.upload(new UploadCommand(901L, "temporary-retry.pdf", "application/pdf",
            bytes.length, new ByteArrayInputStream(bytes), CorrelationId.random())))
            .isInstanceOfSatisfying(StorageUnavailableException.class, unavailable -> {
                assertThat(unavailable.retryable()).isTrue();
                assertThat(unavailable.failureClass()).isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
            });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file WHERE status='ACTIVE'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(reference_count),0) FROM stored_blob", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE status='FAILED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT'", Integer.class)).isOne();

        cutMinioConnection(false);
        UploadResult recovered = uploadService.upload(new UploadCommand(901L, "temporary-retry.pdf", "application/pdf",
            bytes.length, new ByteArrayInputStream(bytes), CorrelationId.random()));
        assertThat(recovered.fileId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file WHERE status='ACTIVE'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='READY' AND reference_count=1", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE status='COMPLETED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT' AND status='NEW'", Integer.class)).isOne();
    }
}
