package com.example.files.unit;

import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditSanitizer;
import com.example.files.application.audit.CorrelationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSanitizerTest {
    @Test
    void persistsOnlyAllowListedFailureCodeAndSafeTraceSummary() {
        AuditSanitizer sanitizer = new AuditSanitizer();
        AuditEvent event = new AuditEvent(CorrelationId.random(), 7L, AuditAction.FILE_ACCESSED,
            UUID.randomUUID(), null, "FAILED", "STORAGE_READ_FAILED",
            "trace-7", Instant.now());

        AuditEvent safe = sanitizer.sanitize(event);

        assertThat(safe.failureCode()).isEqualTo("STORAGE_READ_FAILED");
        assertThat(sanitizer.sanitizeTrace("tmp/secret\r\nAuthorization: Bearer jwt-hmac-X-Amz-Signature"))
            .isNull();
    }

    @Test
    void replacesObjectKeysHashesSecretsJwtHmacAndExceptionTextWithFixedCode() {
        AuditSanitizer sanitizer = new AuditSanitizer();
        assertThat(sanitizer.sanitizeFailureCode("TMP_BLOBS_SHA256_SECRET_JWT_HMAC_X_AMZ_SIGNATURE")).isEqualTo("UNCLASSIFIED");
        assertThat(sanitizer.sanitizeFailureCode("java.io.IOException: connection failed")).isEqualTo("UNCLASSIFIED");
        assertThat(sanitizer.sanitizeTrace("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")).isNull();
        assertThat(sanitizer.sanitizeTrace("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature-value")).isNull();
        assertThat(sanitizer.sanitizeTrace("minioadmin-local-secret-value")).isNull();
        assertThat(sanitizer.sanitizeTrace("tmp/123e4567-e89b-12d3-a456-426614174000")).isNull();
        assertThat(sanitizer.sanitizeTrace("https://minio.test/blobs/object?X-Amz-Signature=secret")).isNull();
        assertThat(sanitizer.sanitizeTrace("hmac=0123456789abcdef0123456789abcdef")).isNull();
        assertThat(sanitizer.sanitizeTrace("java.io.IOException: connection reset by peer")).isNull();
    }

    @Test
    void rejectsNullAndUnsafeResultAtPersistenceBoundary() {
        AuditSanitizer sanitizer = new AuditSanitizer();
        assertThatThrownBy(() -> sanitizer.sanitize(null)).isInstanceOf(IllegalArgumentException.class);
        AuditEvent event = new AuditEvent(CorrelationId.random(), 7L, AuditAction.FILE_ACCESSED,
            null, null, "not-safe", null);
        assertThatThrownBy(() -> sanitizer.sanitize(event)).isInstanceOf(IllegalArgumentException.class);
    }
}
