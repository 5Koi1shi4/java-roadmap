package com.example.files.integration;

import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.infrastructure.storage.StorageUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通过代理切断真实连接，验证可重试故障与提交恢复。 */
class MinioFaultRecoveryIT extends SharedStorageContainers {
    private final MinioObjectStorage storage = new MinioObjectStorage(minioClient(), minioStorage());

    MinioFaultRecoveryIT() { storage.initialize(); }

    @AfterEach
    void restoreProxy() { cutMinioConnection(false); }

    @Test
    void commitConnectionCutIsRetryableAndRecoveryLeavesReadyObjectWithoutTempOrphan() throws Exception {
        String temp = "tmp/" + UUID.randomUUID();
        String object = "blobs/" + UUID.randomUUID();
        byte[] bytes = "recovery".getBytes(StandardCharsets.US_ASCII);
        storage.writeTemporary(temp, new ByteArrayInputStream(bytes), 1024);
        cutMinioConnection(true);
        assertThatThrownBy(() -> storage.commit(temp, object)).isInstanceOf(StorageUnavailableException.class);
        cutMinioConnection(false);
        storage.commit(temp, object);
        assertThat(storage.stat(object)).isEqualTo(new StorageObjectMetadata(bytes.length));
        assertThat(storage.exists(temp)).isFalse();
        assertThat(storage.open(object).readAllBytes()).containsExactly(bytes);
    }

    @Test
    void connectionCutDuringWriteDoesNotCreateActiveObjectAndRetryWritesSuccessfully() {
        String temp = "tmp/" + UUID.randomUUID();
        byte[] bytes = "temporary-retry".getBytes(StandardCharsets.US_ASCII);
        cutMinioConnection(true);
        assertThatThrownBy(() -> storage.writeTemporary(temp, new ByteArrayInputStream(bytes), 1024))
            .isInstanceOf(StorageUnavailableException.class);
        cutMinioConnection(false);
        storage.writeTemporary(temp, new ByteArrayInputStream(bytes), 1024);
        assertThat(storage.stat(temp).size()).isEqualTo(bytes.length);
        storage.delete(temp);
    }
}
