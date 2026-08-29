package com.example.files.unit;

import com.example.files.application.audit.FileServiceMetrics;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.config.FileServiceProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageMetricsTest {
    @Test
    void localStorageRecordsSuccessAndValidationFailureForEveryOperation() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        Path root = Files.createTempDirectory("storage-metrics-test");
        LocalObjectStorage storage = new LocalObjectStorage(root, metrics);
        String temp = "tmp/" + UUID.randomUUID();
        String blob = "blobs/" + UUID.randomUUID();
        storage.writeTemporary(temp, new ByteArrayInputStream(new byte[] {1}), 10);
        storage.commit(temp, blob);
        storage.open(blob).close();
        storage.stat(blob);
        storage.createPresignedGet(blob, Duration.ofSeconds(1), java.util.Map.of());
        storage.delete(blob);
        assertThatThrownBy(() -> storage.writeTemporary("tmp/not-a-uuid", new ByteArrayInputStream(new byte[] {1}), 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.commit("tmp/not-a-uuid", blob)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("blobs/not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.stat("blobs/not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("blobs/not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.createPresignedGet("blobs/not-a-uuid", Duration.ofSeconds(1), java.util.Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        for (String operation : List.of("write_temporary", "commit", "open", "stat", "delete", "presign")) {
            assertThat(metrics.events).anyMatch(event -> event.equals(operation + ":success"));
            assertThat(metrics.events).anyMatch(event -> event.equals(operation + ":failed"));
        }
    }

    @Test
    void minioStorageRejectsUnsafeKeysWithFailureTimingWithoutSdkCall() {
        RecordingMetrics metrics = new RecordingMetrics();
        FileServiceProperties.Storage config = new FileServiceProperties.Storage(
            "minio", "target/minio-metrics", "http://localhost:9000", "access", "secret", "secure-files");
        MinioObjectStorage storage = new MinioObjectStorage(MinioClient.builder().endpoint("http://localhost:9000").build(),
            config, Duration.ofSeconds(2), metrics);
        assertThatThrownBy(() -> storage.stat("blobs/not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThat(metrics.events).contains("stat:failed");
    }

    private static final class RecordingMetrics implements FileServiceMetrics {
        private final List<String> events = new ArrayList<>();
        @Override public void recordStorageOperation(String operation, String result, Duration duration) {
            events.add(operation + ":" + result);
            assertThat(duration == null || duration.isNegative()).isFalse();
        }
        @Override public void recordUpload(String result, Duration duration) { }
        @Override public void recordSession(String status) { }
        @Override public void setSessionCount(String status, long count) { }
        @Override public void recordBlob(String status) { }
        @Override public void setBlobCount(String status, long count) { }
        @Override public void setStagingOldest(Duration age) { }
        @Override public void recordCleanupPending(String type) { }
        @Override public void setCleanupPending(String type, long count) { }
        @Override public void recordCleanupRetry(String type, String result) { }
        @Override public void recordDownload(String phase, String result) { }
        @Override public void recordAcl(String action, String result) { }
    }
}
