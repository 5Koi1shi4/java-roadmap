package com.example.files.application.audit;

import java.time.Instant;
import java.util.UUID;

/** 审计事件数据；不允许携带令牌、对象 Key、哈希或原始异常。 */
public record AuditEvent(CorrelationId correlationId, long actorId, AuditAction action,
                         UUID fileId, Long targetUserId, String result,
                         String failureCode, String clientTraceId, Instant createdAt) {
    public AuditEvent {
        if (correlationId == null || actorId <= 0 || action == null
            || result == null || result.isBlank() || result.length() > 24 || createdAt == null) {
            throw new IllegalArgumentException("invalid audit event");
        }
        if (targetUserId != null && targetUserId <= 0) {
            throw new IllegalArgumentException("targetUserId must be positive");
        }
        if (failureCode != null && failureCode.length() > 64) {
            throw new IllegalArgumentException("failureCode is too long");
        }
        if (clientTraceId != null && !clientTraceId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid clientTraceId");
        }
    }

    public AuditEvent(CorrelationId correlationId, long actorId, AuditAction action,
                      UUID fileId, Long targetUserId, String result, String failureCode) {
        this(correlationId, actorId, action, fileId, targetUserId, result, failureCode, null, Instant.now());
    }
}
