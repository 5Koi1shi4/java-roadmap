package com.example.files.application.audit;

import java.time.Instant;
import java.util.UUID;

/** 审计事件数据；不允许携带令牌、对象 Key、哈希或原始异常。 */
public record AuditEvent(CorrelationId correlationId, long actorId, AuditAction action,
                         UUID fileId, Long targetUserId, String result,
                         String failureCode, String clientTraceId, Instant createdAt, Instant expiresAt) {
    public AuditEvent {
        if (correlationId == null || actorId <= 0 || action == null
            || result == null || result.isBlank() || result.length() > 24 || createdAt == null) {
            throw new IllegalArgumentException("invalid audit event");
        }
        if (targetUserId != null && targetUserId <= 0) {
            throw new IllegalArgumentException("targetUserId must be positive");
        }
        // Raw producer text is accepted only as bounded, single-line data; the
        // persistence adapter applies the fixed failure-code allow-list. Keeping
        // this boundary permissive lets the recorder sanitize misbehaving callers
        // instead of allowing constructor validation to bypass that guarantee.
        if (failureCode != null && (failureCode.length() > 64
            || failureCode.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("invalid failureCode");
        }
        // Protocol/header validation belongs at the HTTP boundary. At the audit
        // boundary retain only bounded single-line input and let AuditSanitizer
        // discard keys, URLs, credentials and other sensitive trace text.
        if (clientTraceId != null && (clientTraceId.length() > 128
            || clientTraceId.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("invalid clientTraceId");
        }
    }

    public AuditEvent(CorrelationId correlationId, long actorId, AuditAction action,
                      UUID fileId, Long targetUserId, String result, String failureCode) {
        this(correlationId, actorId, action, fileId, targetUserId, result, failureCode, null, Instant.now(), null);
    }

    public AuditEvent(CorrelationId correlationId, long actorId, AuditAction action,
                      UUID fileId, Long targetUserId, String result, String failureCode,
                      String clientTraceId, Instant createdAt) {
        this(correlationId, actorId, action, fileId, targetUserId, result, failureCode,
            clientTraceId, createdAt, null);
    }
}
