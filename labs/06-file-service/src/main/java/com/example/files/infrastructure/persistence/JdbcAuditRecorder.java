package com.example.files.infrastructure.persistence;

import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.AuditSanitizer;
import org.springframework.jdbc.core.JdbcTemplate;

/** 审计 JDBC 记录器；只写有限字段并参与调用方事务。 */
public final class JdbcAuditRecorder implements AuditRecorder {
    private final JdbcTemplate jdbc;
    private final AuditSanitizer sanitizer;

    public JdbcAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.sanitizer = new AuditSanitizer();
    }

    @Override
    public void record(AuditEvent event) {
        event = sanitizer.sanitize(event);
        jdbc.update("INSERT INTO file_audit_event(correlation_id,actor_id,action,file_id,target_user_id,result,"
                + "failure_code,client_trace_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
            event.correlationId().value(), event.actorId(), event.action().name(),
            event.fileId() == null ? null : event.fileId().toString(), event.targetUserId(), event.result(),
            event.failureCode(), event.clientTraceId(), event.expiresAt());
    }
}
