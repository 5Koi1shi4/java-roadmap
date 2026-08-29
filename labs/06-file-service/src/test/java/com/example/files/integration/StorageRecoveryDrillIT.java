package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StagingRecoveryService;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.config.FileServiceProperties;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.UploadSession;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.infrastructure.storage.StorageUnavailableException;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.RemoveObjectArgs;
import io.minio.messages.Item;
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
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同一组真实 MySQL/MinIO/Toxiproxy 容器连续三轮故障、恢复和双重终态演练。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"file.identity.trusted-header-enabled=true", "file.storage.type=minio",
        "file.maintenance.enabled=true", "file.cleanup.retry-delays=50ms", "file.cleanup.schedule=1h"})
@ActiveProfiles("test")
class StorageRecoveryDrillIT extends SharedStorageContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcCleanupTaskRepository tasks;
    @Autowired private UploadTransactionService transactions;
    @Autowired private StagingRecoveryService stagingRecovery;
    @Autowired private StorageCleanupService cleanup;
    @Autowired private ObjectStorage storage;
    @Autowired private FileServiceProperties properties;
    @Autowired private MinioObjectStorage minio;
    @Autowired private com.example.files.observability.UploadRecoveryScheduler scheduler;

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) { registerStorageProperties(registry); }

    @BeforeEach
    void clearDatabaseAndBucket() throws Exception {
        cutMinioConnection(false);
        jdbc.update("DELETE FROM storage_cleanup_task");
        jdbc.update("DELETE FROM file_audit_event");
        jdbc.update("DELETE FROM file_grant");
        jdbc.update("DELETE FROM stored_file");
        jdbc.update("DELETE FROM stored_blob");
        jdbc.update("DELETE FROM upload_session");
        for (Result<Item> result : minioClient().listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            minioClient().removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(result.get().objectName()).build());
        }
    }

    @AfterEach
    void restoreProxy() { cutMinioConnection(false); }

    @Test
    void recoversThreeConsecutiveMinioOutagesOnOriginalSessionAndScansWholeBucket() throws Exception {
        for (int round = 1; round <= 3; round++) {
            byte[] bytes = ("%PDF-1.7\nthree-round-drill-" + round).getBytes(StandardCharsets.US_ASCII);
            String hash = hexSha256(bytes);
            UploadSession session = transactions.begin(700L + round,
                com.example.files.domain.SafeDisplayName.from("故障轮次-" + round + ".pdf"), "application/pdf",
                properties.uploadSessionTtl(), properties.stagingLease());
            String tempKey = session.tempKey();
            storage.writeTemporary(tempKey, new ByteArrayInputStream(bytes), properties.maxBytes());
            InspectedUpload inspected = new InspectedUpload(
                com.example.files.domain.SafeDisplayName.from("故障轮次-" + round + ".pdf"), "application/pdf",
                DetectedFileType.PDF, bytes.length, hash, new TemporaryObject(tempKey, bytes.length));
            BlobReservation.Granted reservation = (BlobReservation.Granted)
                transactions.reserve(session.sessionId(), session.ownerToken(), inspected, properties.stagingLease());
            long blobId = reservation.blobId();
            String objectKey = reservation.objectKey();

            cutMinioConnection(true);
            assertThatThrownBy(() -> storage.commit(tempKey, objectKey))
                .isInstanceOf(StorageUnavailableException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
                session.sessionId().toString())).isEqualTo("VALIDATED");
            assertThat(jdbc.queryForObject("SELECT status FROM stored_blob WHERE id=?", String.class, blobId))
                .isEqualTo("STAGING");

            cutMinioConnection(false);
            // Re-attempt the physical operation for the same temp/blob pair. No
            // second upload or replacement session is allowed in this drill.
            storage.commit(tempKey, objectKey);
            jdbc.update("UPDATE upload_session SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND),"
                + "expires_at=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE session_id=?",
                session.sessionId().toString());
            jdbc.update("UPDATE stored_blob SET staging_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE id=?", blobId);

            scheduler.run();
            org.awaitility.Awaitility.await().untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE session_id=?", String.class,
                    session.sessionId().toString())).isEqualTo("COMPLETED");
                assertThat(jdbc.queryForObject("SELECT blob_id FROM stored_file WHERE file_id=(SELECT file_id FROM upload_session WHERE session_id=?)",
                    Long.class, session.sessionId().toString())).isEqualTo(blobId);
                assertThat(jdbc.queryForObject("SELECT status FROM stored_blob WHERE id=?", String.class, blobId))
                    .isEqualTo("READY");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT' AND target_id=? AND status='COMPLETED'",
                    Integer.class, session.sessionId().toString())).isOne();
            });
            String taskKey = jdbc.queryForObject("SELECT object_key FROM storage_cleanup_task WHERE task_type='TEMP_OBJECT' AND target_id=?",
                String.class, session.sessionId().toString());
            assertThat(taskKey).isEqualTo(tempKey);
            assertThat(minio.exists(tempKey)).isFalse();
            assertThat(minio.exists(objectKey)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('NEW','PROCESSING')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE status='STAGING'", Integer.class)).isZero();
            assertWholeBucketMatchesReadyRows();
        }
    }

    private void assertWholeBucketMatchesReadyRows() throws Exception {
        Set<String> physical = new HashSet<>();
        for (Result<Item> result : minioClient().listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            String key = result.get().objectName();
            assertThat(key).startsWith("blobs/");
            physical.add(key);
        }
        Set<String> ready = new HashSet<>(jdbc.queryForList("SELECT object_key FROM stored_blob WHERE status='READY'", String.class));
        assertThat(physical).containsExactlyInAnyOrderElementsOf(ready);
    }

    private static String hexSha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
