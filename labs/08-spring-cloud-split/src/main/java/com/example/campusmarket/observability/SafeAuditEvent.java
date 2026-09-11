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
    private static final Set<String> FORBIDDEN_TERMS = Set.of(
        "邮箱", "令牌", "验证码", "签名", "对象键", "预签名", "密码", "密钥", "堆栈", "异常", "路径", "哈希");
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
        action = safeLabel(action, "action");
        resourceType = safeLabel(resourceType, "resourceType");
        result = requireText(result, "result");
        if (!Set.of("SUCCESS", "FAILURE").contains(result)) {
            throw new IllegalArgumentException("审计结果无效");
        }
        if ("FAILURE".equals(result)) {
            failureClass = safeLabel(failureClass, "failureClass");
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
            String normalized = normalize(key);
            if (isSensitive(key)) {
                throw new IllegalArgumentException("审计字段包含敏感信息: " + key);
            }
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
                safe.put(key, value);
                continue;
            }
            if (value instanceof CharSequence text) {
                String string = text.toString();
                if (isSensitive(string)) {
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

    private static String safeLabel(String value, String field) {
        String label = requireText(value, field);
        String normalized = normalize(label);
        if (isSensitive(label)) {
            throw new IllegalArgumentException("审计字段包含敏感信息: " + field);
        }
        return label;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isSensitive(String value) {
        String normalized = normalize(value);
        return FORBIDDEN_KEYS.stream().anyMatch(normalized::contains)
            || FORBIDDEN_TERMS.stream().anyMatch(value::contains)
            || EMAIL.matcher(value).find() || SIGNED_URL.matcher(value).find()
            || OBJECT_KEY.matcher(value).find() || STACK.matcher(value).find()
            || TOKEN.matcher(value).find() || looksLikeBareObjectKey(value);
    }

    private static boolean looksLikeBareObjectKey(String value) {
        return value.matches("[A-Za-z0-9_-]{24,}") && value.matches(".*[A-Za-z].*")
            && value.matches(".*\\d.*");
    }
}
