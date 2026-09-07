package com.example.campusmarket.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 审计边界上的不可变安全事件。敏感字段在进入 JDBC、日志或消息之前即被拒绝。
 */
public record SafeAuditEvent(
    String correlationId,
    UUID actorId,
    String action,
    String resourceType,
    UUID resourceId,
    String result,
    String failureClass,
    Map<String, Object> details,
    Instant occurredAt) {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
        "token", "accesstoken", "refreshtoken", "verificationcode", "code", "otp",
        "signature", "signaturevalue", "objectkey", "object_key", "presignedurl",
        "presigned_url", "email", "password", "secret", "stack", "stacktrace",
        "exception", "authorization", "cookie", "path", "hash", "contenthash");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\b");
    private static final Pattern SIGNED_URL = Pattern.compile("(?i)(x-amz-signature|signature=|presign|presigned)");
    private static final Pattern TOKEN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~-]+|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern OBJECT_KEY = Pattern.compile("(?i)(^|[\\s/])(tmp|blobs?)/|object[-_ ]?key");
    private static final Pattern STACK = Pattern.compile("(?m)^\\s*at\\s+[^\\n]+|java\\.(lang|util)\\.[A-Za-z]+Exception");

    public SafeAuditEvent {
        correlationId = requireText(correlationId, "correlationId");
        try {
            correlationId = UUID.fromString(correlationId).toString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("correlationId 必须为 UUID", ex);
        }
        action = requireText(action, "action");
        resourceType = requireText(resourceType, "resourceType");
        result = requireText(result, "result");
        if (!Set.of("SUCCESS", "FAILURE").contains(result)) {
            throw new IllegalArgumentException("审计结果无效");
        }
        if ("FAILURE".equals(result)) {
            failureClass = requireText(failureClass, "failureClass");
            if (!Set.of("VALIDATION", "AUTHENTICATION", "AUTHORIZATION", "CONFLICT", "DEPENDENCY", "INTERNAL").contains(failureClass)) {
                throw new IllegalArgumentException("审计失败分类无效");
            }
        } else if (failureClass != null) {
            throw new IllegalArgumentException("成功审计不能有失败分类");
        }
        details = sanitize(details);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }

    public static SafeAuditEvent success(UUID actorId, String action, String resourceType,
                                         UUID resourceId, Map<String, ?> details) {
        return create(actorId, action, resourceType, resourceId, "SUCCESS", null, details);
    }

    public static SafeAuditEvent failure(UUID actorId, String action, String resourceType,
                                         UUID resourceId, String failureClass, Map<String, ?> details) {
        return create(actorId, action, resourceType, resourceId, "FAILURE", failureClass, details);
    }

    public static SafeAuditEvent create(UUID actorId, String action, String resourceType,
                                        UUID resourceId, String result, String failureClass,
                                        Map<String, ?> details) {
        return new SafeAuditEvent(UUID.randomUUID().toString(), actorId, action, resourceType,
            resourceId, result, failureClass, copy(details), Instant.now());
    }

    /** 提供给持久化适配器和单元测试的显式过滤入口。 */
    public static Map<String, Object> sanitizeDetails(Map<String, ?> details) {
        return sanitize(copy(details));
    }

    private static Map<String, Object> copy(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> copy.put(key, value));
        return copy;
    }

    private static Map<String, Object> sanitize(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (source == null) return Map.of();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = requireText(entry.getKey(), "审计字段");
            String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace(".", "");
            if (FORBIDDEN_KEYS.contains(normalized) || FORBIDDEN_KEYS.stream().anyMatch(normalized::contains)) {
                throw new IllegalArgumentException("审计字段包含敏感信息: " + key);
            }
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
                safe.put(key, value);
                continue;
            }
            if (value instanceof CharSequence text) {
                String string = text.toString();
                if (EMAIL.matcher(string).find() || SIGNED_URL.matcher(string).find()
                    || OBJECT_KEY.matcher(string).find() || STACK.matcher(string).find()
                    || TOKEN.matcher(string).find()) {
                    throw new IllegalArgumentException("审计字段包含敏感信息: " + key);
                }
                safe.put(key, string);
                continue;
            }
            throw new IllegalArgumentException("审计字段类型不受支持: " + key);
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(safe));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }
}
