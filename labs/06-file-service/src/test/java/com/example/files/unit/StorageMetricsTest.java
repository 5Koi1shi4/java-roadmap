package com.example.files.unit;

import com.example.files.application.audit.FileServiceMetrics;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.config.FileServiceProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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

    @Test
    void minioWriteTemporaryRecordsExactlyOneFailureForValidation() {
        RecordingMetrics metrics = new RecordingMetrics();
        MinioObjectStorage storage = minioStorage(mock(MinioClient.class), metrics);

        assertThatThrownBy(() -> storage.writeTemporary("tmp/not-a-uuid",
            new ByteArrayInputStream(new byte[] {1}), 10)).isInstanceOf(IllegalArgumentException.class);

        assertThat(metrics.events).filteredOn(event -> event.startsWith("write_temporary:"))
            .containsExactly("write_temporary:failed");
    }

    @Test
    void minioWriteTemporaryRecordsExactlyOneFailureForSdkCheckedAndRuntimeErrors() throws Exception {
        for (Throwable failure : List.of(new java.io.IOException("io failure"),
            new IllegalStateException("sdk failure"))) {
            RecordingMetrics metrics = new RecordingMetrics();
            MinioClient client = mock(MinioClient.class);
            if (failure instanceof java.io.IOException io) {
                doThrow(io).when(client).putObject(any(PutObjectArgs.class));
            } else {
                doThrow((RuntimeException) failure).when(client).putObject(any(PutObjectArgs.class));
            }
            MinioObjectStorage storage = minioStorage(client, metrics);

            assertThatThrownBy(() -> storage.writeTemporary("tmp/00000000-0000-0000-0000-000000000000",
                new ByteArrayInputStream(new byte[] {1}), 10)).isInstanceOf(RuntimeException.class);

            assertThat(metrics.events).filteredOn(event -> event.startsWith("write_temporary:"))
                .containsExactly("write_temporary:failed");
        }
    }

    @Test
    void minioWriteTemporaryMapsReadOverflowAndRecordsExactlyOneFailure() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        MinioClient client = mock(MinioClient.class);
        doAnswer(invocation -> {
            InputStream source = invocation.getArgument(0, PutObjectArgs.class).stream();
            while (source.read() >= 0) { }
            return null;
        }).when(client).putObject(any(PutObjectArgs.class));
        MinioObjectStorage storage = minioStorage(client, metrics);

        assertThatThrownBy(() -> storage.writeTemporary("tmp/00000000-0000-0000-0000-000000000000",
            new ByteArrayInputStream(new byte[] {1, 2}), 1))
            .isInstanceOf(com.example.files.application.upload.UploadRejectedException.class);

        assertThat(metrics.events).filteredOn(event -> event.startsWith("write_temporary:"))
            .containsExactly("write_temporary:failed");
    }

    private static MinioObjectStorage minioStorage(MinioClient client, RecordingMetrics metrics) {
        FileServiceProperties.Storage storage = new FileServiceProperties.Storage(
            "minio", "target/minio-metrics", "http://localhost:9000", "access", "secret", "secure-files");
        return new MinioObjectStorage(client, storage, Duration.ofSeconds(2), metrics);
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
