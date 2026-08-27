package com.example.files.application.audit;

import java.security.SecureRandom;
import java.util.Base64;

/** 服务端生成的请求关联标识，至少含 128 位随机熵。 */
public record CorrelationId(String value) {
    private static final SecureRandom RANDOM = new SecureRandom();

    public CorrelationId {
        if (!isValid(value)) {
            throw new IllegalArgumentException("invalid correlation id");
        }
    }

    public static CorrelationId random() {
        byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        return new CorrelationId(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }

    /** 兼容历史 UUID 审计值，同时只接受服务端格式或规范 UUID，拒绝任意外部字符串。 */
    public static boolean isValid(String value) {
        if (value == null) return false;
        if (value.matches("[A-Za-z0-9_-]{22}")) {
            try {
                return Base64.getUrlDecoder().decode(value).length == 16;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return value.length() == 36
            && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    }
}
