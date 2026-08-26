package com.example.files.application.audit;

import java.util.UUID;

/** 服务端生成的请求关联标识，至少含 128 位随机熵。 */
public record CorrelationId(String value) {
    public CorrelationId {
        if (value == null || value.length() != 36
            || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("invalid correlation id");
        }
    }

    public static CorrelationId random() {
        return new CorrelationId(UUID.randomUUID().toString());
    }
}
