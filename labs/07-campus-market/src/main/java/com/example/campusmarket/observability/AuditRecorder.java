package com.example.campusmarket.observability;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.Map;

/** 审计持久化端口；数据库不可用时故意向上抛出异常，不能静默放行安全操作。 */
@Service
@Profile("!test")
public final class AuditRecorder {
    private final JdbcTemplate jdbc;

    public AuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "审计数据库不能为空");
    }

    public void record(SafeAuditEvent event) {
        Objects.requireNonNull(event, "审计事件不能为空");
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,failure_class,details,occurred_at) "
                + "VALUES (?,?,?,?,?,?,?,CAST(? AS JSON),CURRENT_TIMESTAMP(6))",
            event.correlationId(), event.actorId() == null ? null : event.actorId().toString(), event.action(),
            event.resourceType(), event.resourceId() == null ? null : event.resourceId().toString(), event.result(),
            event.failureClass(), toJson(event.details()));
    }

    public void recordSuccess(UUID actorId, String action, String resourceType, UUID resourceId,
                              Map<String, ?> details) {
        record(SafeAuditEvent.success(actorId, action, resourceType, resourceId, details));
    }

    public void recordFailure(UUID actorId, String action, String resourceType, UUID resourceId,
                              String failureClass, Map<String, ?> details) {
        record(SafeAuditEvent.failure(actorId, action, resourceType, resourceId, failureClass, details));
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("审计详情无效", ex);
        }
    }
}
