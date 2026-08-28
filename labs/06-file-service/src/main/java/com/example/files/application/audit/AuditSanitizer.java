package com.example.files.application.audit;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 审计持久化边界的唯一脱敏器。调用方即使误把底层错误带到审计事件中，
 * 也只能持久化固定结果、固定失败分类和受限的客户端追踪摘要。
 */
public final class AuditSanitizer {
    public static final String UNCLASSIFIED_FAILURE = "UNCLASSIFIED";
    private static final Set<String> RESULTS = Set.of("SUCCESS", "DENIED", "FAILED", "RETRYABLE");
    private static final Set<String> FAILURE_CODES = Set.of(
        "ACCESS_DENIED", "INVALID_TOKEN", "OBJECT_NOT_FOUND", "STORAGE_READ_FAILED",
        "HEADER_FAILED", "STREAM_FAILED", "ASYNC_ABORTED", "EMPTY_FILE", "FILE_TOO_LARGE",
        "INVALID_SIZE", "READ_FAILED", "TYPE_MISMATCH", "TYPE_UNKNOWN", "UPLOAD_FAILED",
        "STORAGE_COORDINATION_UNAVAILABLE", "STORAGE_IO", "STORAGE_PERMANENT", "STORAGE_RETRYABLE",
        "BLOB_HASH_COORDINATION_RETRY_EXHAUSTED", "BLOB_LEASE_LOST", "BLOB_NOT_FOUND", "BLOB_NOT_READY",
        "BLOB_REFERENCE_FAILED", "UPLOAD_BLOB_BINDING_FAILED", "UPLOAD_BLOB_METADATA_MISMATCH",
        "UPLOAD_BLOB_NOT_BOUND", "UPLOAD_RECOVERY_FENCED", "UPLOAD_RECOVERY_SESSION_LOST",
        "UPLOAD_RECOVERY_STATE_LOST", "UPLOAD_RECOVERY_TAKEOVER_LOST", "UPLOAD_SESSION_COMPLETION_FAILED",
        "UPLOAD_SESSION_LEASE_LOST", "UPLOAD_SESSION_NOT_FOUND", "UPLOAD_SESSION_NOT_OWNED",
        "UPLOAD_SESSION_NOT_VALIDATED", "UPLOAD_TAKEOVER_LOST", "RECOVERY_BLOB_MISSING",
        "RECOVERY_METADATA_MISMATCH", "RECOVERY_OBJECT_MISMATCH", "RECOVERY_OBJECT_MISSING",
        "FILE_STATE_INVALID", "BLOB_REFERENCE_STATE_INVALID", "BLOB_REFERENCE_UPDATE_FAILED",
        "SERVICE_UNAVAILABLE", "UNAUTHORIZED", "INVALID_REQUEST");

    public AuditEvent sanitize(AuditEvent event) {
        if (event == null) throw new IllegalArgumentException("audit event must not be null");
        String result = normalizeResult(event.result());
        String failure = sanitizeFailureCode(event.failureCode());
        String trace = sanitizeTrace(event.clientTraceId());
        return new AuditEvent(event.correlationId(), event.actorId(), event.action(), event.fileId(),
            event.targetUserId(), result, failure, trace, event.createdAt(), event.expiresAt());
    }

    /** 只返回代码内固定 allow-list 中的错误分类。 */
    public String sanitizeFailureCode(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        return FAILURE_CODES.contains(candidate) ? candidate : UNCLASSIFIED_FAILURE;
    }

    /** 客户端追踪字段只保留有限字符；发现秘密、路径或换行时整体丢弃。 */
    public String sanitizeTrace(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 128 || containsSecret(value)) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]", "");
        return cleaned.matches("[A-Za-z0-9._:-]{1,128}") ? cleaned : null;
    }

    private static String normalizeResult(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("audit result is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!RESULTS.contains(normalized)) throw new IllegalArgumentException("unsupported audit result");
        return normalized;
    }

    private static boolean containsSecret(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("tmp/") || lower.contains("blobs/") || lower.contains("sha-256")
            || lower.contains("sha256") || lower.contains("x-amz-") || lower.contains("authorization")
            || lower.contains("bearer") || lower.contains("jwt") || lower.contains("hmac")
            || lower.contains("secret") || lower.contains("exception") || lower.contains("http://")
            || lower.contains("https://");
    }
}
