package com.example.files.application.audit;

import java.security.SecureRandom;
import java.util.Base64;

/** 服务端生成的请求关联标识，至少含 128 位随机熵。 */
public record CorrelationId(String value) {
    private static final SecureRandom RANDOM = new SecureRandom();

    public CorrelationId {
        if (value == null || value.isBlank() || value.length() > 128
            || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid correlation id");
        }
    }

    public static CorrelationId random() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return new CorrelationId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
