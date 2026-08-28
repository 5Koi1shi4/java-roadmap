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
