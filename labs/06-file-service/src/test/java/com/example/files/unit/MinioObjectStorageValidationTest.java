package com.example.files.unit;

import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.config.FileServiceProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioObjectStorageValidationTest {
    @Test
    void rejectsTraversalAndCrossNamespaceKeysBeforeSdkCalls() {
        assertThatThrownBy(() -> MinioObjectStorage.validateKey("../tmp/00000000-0000-0000-0000-000000000000", "tmp"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinioObjectStorage.validateKey("blobs/00000000-0000-0000-0000-000000000000", "tmp"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinioObjectStorage.validateKey("tmp/not-a-uuid", "tmp"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesSafeResponseHeadersToSdkPresignArguments() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any(GetPresignedObjectUrlArgs.class)))
            .thenReturn("http://minio.test/object");
        FileServiceProperties.Storage storage = new FileServiceProperties.Storage(
            "minio", "./data/files", "http://minio.test", "access", "secret", "secure-files");
        MinioObjectStorage objectStorage = new MinioObjectStorage(client, storage);

        objectStorage.createPresignedGet("blobs/00000000-0000-0000-0000-000000000000", Duration.ofSeconds(30),
            Map.of("response-content-type", "application/pdf"));

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        org.mockito.Mockito.verify(client).getPresignedObjectUrl(captor.capture());
        assertThat(captor.getValue().extraQueryParams().get("response-content-type"))
            .contains("application/pdf");
    }

}
