package com.example.campusmarket.identity.application;

import java.util.Map;
import java.util.Objects;

/** 外部身份回调结果；具体 OIDC/CAS 适配器由后续任务接入。 */
public record ExternalIdentity(String provider, String subject, Map<String, String> attributes) {
    public ExternalIdentity {
        requireText(provider, "身份提供方");
        requireText(subject, "外部主体");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空白");
        }
    }
}
