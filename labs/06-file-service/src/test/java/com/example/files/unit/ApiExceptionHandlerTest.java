package com.example.files.unit;

import com.example.files.api.ApiError;
import com.example.files.api.ApiExceptionHandler;
import com.example.files.application.upload.UploadRejectedException;
import com.example.files.application.audit.CorrelationId;
import com.example.files.infrastructure.storage.StorageFailureClassifier;
import com.example.files.infrastructure.storage.StorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsUploadLimitTo413WithoutExposingFailureDetail() {
        ResponseEntity<ApiError> response = handler.handleUploadRejected(
            new UploadRejectedException("FILE_TOO_LARGE", "secret /path hash"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).extracting(ApiError::code, ApiError::message)
            .containsExactly("FILE_TOO_LARGE", "文件超过大小限制");
        assertThat(response.getBody().message()).doesNotContain("secret", "/path", "hash");
    }

    @Test
    void ignoresStringCorrelationAttributeAndUsesOnlyTypedInternalId() {
        MockHttpServletRequest request = request();
        request.setAttribute("file.correlationId", "550e8400-e29b-41d4-a716-446655440000");
        ResponseEntity<ApiError> response = handler.handleUnauthorized(
            new com.example.files.api.security.RequesterUnauthenticatedException(), request);

        assertThat(response.getBody().correlationId()).isNotEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(CorrelationId.isValid(response.getBody().correlationId())).isTrue();
    }

    @Test
    void mapsUnknownBucketStorageFailureToSafe503() {
        ResponseEntity<ApiError> response = handler.handleUnavailable(
            new StorageUnavailableException("stat", StorageFailureClassifier.FailureClass.PERMANENT), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).extracting(ApiError::code, ApiError::message)
            .containsExactly("SERVICE_UNAVAILABLE", "文件服务暂不可用");
        assertThat(response.getBody().message()).doesNotContain("secure-files", "blobs/");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("file.correlationId", "AAAAAAAAAAAAAAAAAAAAAA");
        return request;
    }
}
