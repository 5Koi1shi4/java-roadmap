package com.example.campusmarket.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A short-lived-looking cursor is not enough for an ACL endpoint: the cursor
 * must not be transferable between users or resource types.  The signed
 * payload therefore carries both bindings as well as the keyset position.
 */
@Component
public final class SupportCursor {
    private static final int MAX_BYTES = 512;
    private static final String VERSION = "v1";
    private static final Set<String> TYPES = Set.of("orders", "disputes", "warranties");
    private static final String DEFAULT_SECRET =
        "campus-market-support-cursor-local-secret-change-me-please";

    private final byte[] secret;

    public SupportCursor(@Value("${campus.market.support.cursor-secret:" + DEFAULT_SECRET + "}") String secret) {
        Objects.requireNonNull(secret, "游标密钥不能为空");
        byte[] encoded = secret.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 32) {
            throw new IllegalArgumentException("游标密钥至少需要 32 字节");
        }
        this.secret = encoded.clone();
    }

    public String encode(UUID userId, String type, Instant createdAt, UUID id) {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(id, "资源 ID 不能为空");
        validateType(type);
        String payload = VERSION + "|" + userId + "|" + type + "|" + createdAt + "|" + id;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encodedPayload));
        String cursor = encodedPayload + "." + signature;
        if (cursor.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("游标过长");
        }
        return cursor;
    }

    public Position decode(String cursor, UUID userId, String type) {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        validateType(type);
        if (cursor == null || cursor.isBlank()
            || cursor.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("游标无效");
        }

        int separator = cursor.indexOf('.');
        if (separator <= 0 || separator != cursor.lastIndexOf('.') || separator == cursor.length() - 1) {
            throw new IllegalArgumentException("游标无效");
        }
        String encodedPayload = cursor.substring(0, separator);
        String encodedSignature = cursor.substring(separator + 1);
        try {
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(encodedSignature);
            byte[] expectedSignature = sign(encodedPayload);
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                throw new IllegalArgumentException("游标无效");
            }
            String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 5 || !VERSION.equals(parts[0])
                || !userId.toString().equals(parts[1]) || !type.equals(parts[2])) {
                throw new IllegalArgumentException("游标无效");
            }
            Instant createdAt = Instant.parse(parts[3]);
            UUID id = canonicalUuid(parts[4]);
            return new Position(createdAt, id);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("游标无效", ex);
        }
    }

    public static void validateType(String type) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("资源类型无效");
        }
    }

    private static UUID canonicalUuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("游标无效");
        }
        return id;
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法签名游标", ex);
        }
    }

    public record Position(Instant createdAt, UUID id) {
        public Position {
            Objects.requireNonNull(createdAt, "创建时间不能为空");
            Objects.requireNonNull(id, "资源 ID 不能为空");
        }
    }
}
