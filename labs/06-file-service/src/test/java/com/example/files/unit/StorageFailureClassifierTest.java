package com.example.files.unit;

import com.example.files.infrastructure.storage.StorageFailureClassifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;

import static org.assertj.core.api.Assertions.assertThat;

class StorageFailureClassifierTest {
    private final StorageFailureClassifier classifier = new StorageFailureClassifier();

    @Test
    void classifiesNetworkFailuresAsRetryable() {
        assertThat(classifier.classify(new SocketTimeoutException("secret=do-not-log")))
            .isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
        assertThat(classifier.classify(new IOException("connection reset")))
            .isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
    }

    @Test
    void classifiesInvalidInputAsPermanent() {
        assertThat(classifier.classify(new IllegalArgumentException("blobs/not-a-uuid")))
            .isEqualTo(StorageFailureClassifier.FailureClass.PERMANENT);
    }

    @Test
    void classifiesMinioServerAndInvalidResponses() {
        assertThat(classifier.classify(new ServerException("server", 503, "request")))
            .isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
        assertThat(classifier.classify(new ServerException("busy", 429, "request")))
            .isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
        assertThat(classifier.classify(new ServerException("forbidden", 403, "request")))
            .isEqualTo(StorageFailureClassifier.FailureClass.PERMANENT);
        assertThat(classifier.classify(new InvalidResponseException(500, "host", "request", "body")))
            .isEqualTo(StorageFailureClassifier.FailureClass.RETRYABLE);
    }
}
