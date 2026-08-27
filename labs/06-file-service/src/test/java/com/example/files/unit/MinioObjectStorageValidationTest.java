package com.example.files.unit;

import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.config.FileServiceProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
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

    @Test
    void mapsNoSuchBucketToPermanentStorageFailureWithoutResourceDetails() throws Exception {
        MinioClient client = mock(MinioClient.class);
        okhttp3.Response response = mock(okhttp3.Response.class);
        when(response.code()).thenReturn(404);
        ErrorResponse error = new ErrorResponse("NoSuchBucket", "bucket missing", "bucket-does-not-exist",
            "blobs/00000000-0000-0000-0000-000000000000", "", "", "");
        when(client.statObject(org.mockito.ArgumentMatchers.any(StatObjectArgs.class)))
            .thenThrow(new ErrorResponseException(error, response, "request"));
        FileServiceProperties.Storage storage = new FileServiceProperties.Storage(
            "minio", "./data/files", "http://minio.test", "access", "secret", "secure-files");
        MinioObjectStorage objectStorage = new MinioObjectStorage(client, storage);

        assertThatThrownBy(() -> objectStorage.stat("blobs/00000000-0000-0000-0000-000000000000"))
            .isInstanceOfSatisfying(com.example.files.infrastructure.storage.StorageUnavailableException.class, failure -> {
                assertThat(failure.failureClass()).isEqualTo(
                    com.example.files.infrastructure.storage.StorageFailureClassifier.FailureClass.PERMANENT);
                assertThat(failure.getMessage()).doesNotContain("secure-files").doesNotContain("blobs/");
            });
    }

}
