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

/** Redis-backed one-time verification codes; only HMAC digests are stored. */
@Component
@Profile("!test")
public class RedisVerificationCodeStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
        "local value = redis.call('GET', KEYS[1]); "
            + "if not value or value ~= ARGV[1] then return 0 end; "
            + "redis.call('DEL', KEYS[1]); return 1", Long.class);

    private final StringRedisTemplate redis;
    private final byte[] secret;
    private final Duration ttl;
    private final Duration rateLimit;
    private final SecureRandom random = new SecureRandom();

    public RedisVerificationCodeStore(StringRedisTemplate redis,
                                      @Value("${campus.market.identity.verification-secret}") String secret,
                                      @Value("${campus.market.identity.verification-ttl:10m}") Duration ttl,
                                      @Value("${campus.market.identity.verification-rate-limit:60s}") Duration rateLimit) {
        this.redis = redis;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.rateLimit = rateLimit;
        if (this.secret.length < 32) {
            throw new IllegalStateException("Verification secret must be at least 256 bits");
        }
    }

    public IssuedCode issue(String email, String purpose) {
        return issue(email, purpose, "unknown-client");
    }

    public IssuedCode issue(String email, String purpose, String client) {
        String rateKey = "campus:verification:rate:" + digest(email + ":" + purpose);
        Boolean accepted = redis.opsForValue().setIfAbsent(rateKey, "1", rateLimit);
        if (!Boolean.TRUE.equals(accepted)) {
            throw new TooManyVerificationRequestsException();
        }
        String clientKey = "campus:verification:client-rate:" + digest(String.valueOf(client));
        Boolean clientAccepted = redis.opsForValue().setIfAbsent(clientKey, "1", rateLimit);
        if (!Boolean.TRUE.equals(clientAccepted)) {
            redis.delete(rateKey);
            throw new TooManyVerificationRequestsException();
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        String hmac = hmac(email, purpose, code);
        redis.opsForValue().set(codeKey(email, purpose), hmac, ttl);
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
            throw new IllegalStateException("Cannot create verification digest", ex);
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

    public record IssuedCode(String code, String hmac) {
    }

    public static final class TooManyVerificationRequestsException extends RuntimeException {
        public TooManyVerificationRequestsException() {
            super("Verification code rate limit exceeded");
        }
    }
}
