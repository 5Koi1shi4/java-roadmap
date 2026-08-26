package com.example.files.application.audit;

/** 审计持久化端口，调用发生在业务事务内部。 */
@FunctionalInterface
public interface AuditRecorder {
    void record(AuditEvent event);
}
