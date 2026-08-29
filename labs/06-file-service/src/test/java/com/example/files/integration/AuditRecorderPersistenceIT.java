package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.CorrelationId;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 验证 JDBC 审计边界按结构化字段持久化脱敏结果。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"file.storage.local-root=target/audit-persistence-storage",
        "file.identity.trusted-header-enabled=true"})
@ActiveProfiles("test")
class AuditRecorderPersistenceIT extends SharedMySqlContainer {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void jdbcBoundaryDoesNotPersistHashJwtSecretKeysUrlsOrExceptionText() {
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(jdbc);
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature-value";
        String secret = "minioadmin-local-secret-value";
        String hmac = "hmac=0123456789abcdef0123456789abcdef";
        String key = "blobs/123e4567-e89b-12d3-a456-426614174000";
        String signedUrl = "https://minio.test/blobs/object?X-Amz-Signature=secret";
        String exception = "java.io.IOException: connection failed";
        recorder.record(new AuditEvent(CorrelationId.random(), 991L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", sha, jwt, Instant.now()));
        recorder.record(new AuditEvent(CorrelationId.random(), 992L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", "STORAGE_READ_FAILED", secret, Instant.now()));
        recorder.record(new AuditEvent(CorrelationId.random(), 993L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", "STORAGE_READ_FAILED", hmac, Instant.now()));
        recorder.record(new AuditEvent(CorrelationId.random(), 994L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", "STORAGE_READ_FAILED", key, Instant.now()));
        recorder.record(new AuditEvent(CorrelationId.random(), 995L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", "STORAGE_READ_FAILED", signedUrl, Instant.now()));
        recorder.record(new AuditEvent(CorrelationId.random(), 996L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", "STORAGE_READ_FAILED", exception, Instant.now()));
        recorder.record(new AuditEvent(CorrelationId.random(), 997L, AuditAction.DOWNLOAD_FAILED,
            UUID.randomUUID(), null, "FAILED", exception, null, Instant.now()));

        List<String> values = jdbc.query("SELECT failure_code,client_trace_id FROM file_audit_event "
            + "WHERE actor_id BETWEEN 991 AND 997 ORDER BY actor_id", (rs, row) ->
            String.valueOf(rs.getString(1)) + "|" + String.valueOf(rs.getString(2)));
        assertThat(values).hasSize(7);
        assertThat(values.get(0)).isEqualTo("UNCLASSIFIED|null");
        assertThat(values.get(1)).isEqualTo("STORAGE_READ_FAILED|null");
        assertThat(values.get(6)).isEqualTo("UNCLASSIFIED|null");
        assertThat(String.join("|", values)).doesNotContain(sha, jwt, secret, hmac, key, signedUrl, exception);
    }
}
