package com.example.campusmarket.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Redis 一次性验证码存储，只保存 HMAC 摘要。 */
@Component
@Profile("!test")
public final class RedisVerificationCodeStore {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int SEND_EMAIL_LIMIT = 3;
    private static final int SEND_IP_LIMIT = 20;
    private static final int SEND_DEVICE_LIMIT = 10;
    private static final int FAIL_EMAIL_LIMIT = 5;
    private static final int FAIL_IP_LIMIT = 20;
    private static final int FAIL_DEVICE_LIMIT = 10;
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
        "local value = redis.call('GET', KEYS[1]); "
            + "if not value or value ~= ARGV[1] then return 0 end; "
            + "redis.call('DEL', KEYS[1]); return 1", Long.class);
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>(
        "local allowed = 1; "
            + "for i = 1, #KEYS do "
            + " local count = redis.call('INCR', KEYS[i]); "
            + " if count == 1 then redis.call('EXPIRE', KEYS[i], ARGV[1]) end; "
            + " if count > tonumber(ARGV[i + 1]) then allowed = 0 end; "
            + "end; return allowed", Long.class);

    private final StringRedisTemplate redis;
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public RedisVerificationCodeStore(StringRedisTemplate redis,
                                      @Value("${campus.market.identity.verification-secret}") String secret) {
        this.redis = Objects.requireNonNull(redis, "Redis 客户端不能为空");
        Objects.requireNonNull(secret, "验证码密钥不能为空");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("验证码密钥至少需要 256 位");
        }
    }

    public IssuedCode issue(String email, String purpose) {
        return issue(email, purpose, "unknown-ip", "unknown-device");
    }

    public IssuedCode issue(String email, String purpose, String client) {
        return issue(email, purpose, client, "unknown-device");
    }

    public IssuedCode issue(String email, String purpose, String remoteIp, String deviceId) {
        requireText(email, "邮箱");
        requireText(purpose, "用途");
        if (!reserve("send", email + ":" + purpose, remoteIp, deviceId,
            SEND_EMAIL_LIMIT, SEND_IP_LIMIT, SEND_DEVICE_LIMIT)) {
            throw new TooManyVerificationRequestsException();
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        String hmac = hmac(email, purpose, code);
        redis.opsForValue().set(codeKey(email, purpose), hmac, WINDOW);
        return new IssuedCode(code, hmac);
    }

    public boolean consume(String email, String purpose, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        String expected = hmac(email, purpose, code);
        Long consumed = redis.execute(CONSUME, List.of(codeKey(email, purpose)), expected);
        return Long.valueOf(1L).equals(consumed);
    }

    public boolean reserveFailureAttempt(String email, String purpose, String remoteIp, String deviceId) {
        return reserve("failure", email + ":" + purpose, remoteIp, deviceId,
            FAIL_EMAIL_LIMIT, FAIL_IP_LIMIT, FAIL_DEVICE_LIMIT);
    }

    private boolean reserve(String kind, String emailPurpose, String remoteIp, String deviceId,
                            int emailLimit, int ipLimit, int deviceLimit) {
        Long allowed = redis.execute(RESERVE,
            List.of(kind + ":email:" + digest(emailPurpose),
                kind + ":ip:" + digest(String.valueOf(remoteIp)),
                kind + ":device:" + digest(String.valueOf(deviceId))),
            String.valueOf(WINDOW.toSeconds()), String.valueOf(emailLimit), String.valueOf(ipLimit),
            String.valueOf(deviceLimit));
        return Long.valueOf(1L).equals(allowed);
    }

    private String codeKey(String email, String purpose) {
        return "campus:verification:code:" + digest(email + ":" + purpose);
    }

    private String hmac(String email, String purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((email + "\u0000" + purpose + "\u0000" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建验证码摘要", ex);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空白");
        }
    }

    public record IssuedCode(String code, String hmac) {
        public IssuedCode {
            requireText(code, "验证码");
            requireText(hmac, "验证码摘要");
        }

        @Override
        public String toString() {
            return "IssuedCode{redacted=true}";
        }
    }

    public static final class TooManyVerificationRequestsException extends RuntimeException {
        public TooManyVerificationRequestsException() {
            super("验证码请求频率超限");
        }
    }
}
