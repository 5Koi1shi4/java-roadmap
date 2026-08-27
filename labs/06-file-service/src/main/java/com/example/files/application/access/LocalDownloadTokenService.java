package com.example.files.application.access;

import com.example.files.config.FileServiceProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 本地下载端点使用的短时、身份绑定令牌。 */
public final class LocalDownloadTokenService {
    public static final Duration MAX_TTL = Duration.ofMinutes(2);
    private static final int NONCE_BYTES = 16;
    private static final int MAC_BYTES = 32;
    private final byte[] secret;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration maxTtl;

    public LocalDownloadTokenService(String secret) {
        this(secret, Clock.systemUTC());
    }

    public LocalDownloadTokenService(String secret, Clock clock) {
        this(secret, clock, MAX_TTL);
    }

    public LocalDownloadTokenService(String secret, Clock clock, Duration maxTtl) {
        if (secret == null) throw new IllegalArgumentException("download signing secret is required");
        byte[] encoded = secret.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 32) throw new IllegalArgumentException("download signing secret must be at least 32 bytes");
        this.secret = encoded.clone();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (maxTtl == null || maxTtl.isZero() || maxTtl.isNegative() || maxTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("invalid token maximum TTL");
        }
        this.maxTtl = maxTtl;
        this.random = new SecureRandom();
    }

    public LocalDownloadTokenService(byte[] secret, Clock clock) {
        this(secret == null ? null : new String(secret, StandardCharsets.UTF_8), clock);
    }

    public LocalDownloadTokenService(FileServiceProperties.Download download) {
        this(download, Clock.systemUTC());
    }

    public LocalDownloadTokenService(FileServiceProperties.Download download, Clock clock) {
        this(download == null ? null : download.localHmacSecret(), clock,
            download == null ? null : download.maxLinkTtl());
    }

    public String issue(long actorId, UUID fileId, Duration ttl) {
        if (actorId <= 0 || fileId == null) throw new IllegalArgumentException("invalid token identity");
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("token ttl must be positive and no more than 2 minutes");
        }
        Instant expires = clock.instant().plus(ttl);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String payload = "1|" + fileId + "|" + actorId + "|" + expires.getEpochSecond()
            + "|" + encode(nonce);
        String payloadPart = encode(payload.getBytes(StandardCharsets.UTF_8));
        return payloadPart + "." + encode(sign(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public String issue(UUID fileId, long actorId, Duration ttl) {
        return issue(actorId, fileId, ttl);
    }

    public String issue(long actorId, UUID fileId, Duration ttl,
                        com.example.files.application.audit.CorrelationId ignoredCorrelationId) {
        return issue(actorId, fileId, ttl);
    }

    public Claims verify(String token, long actorId) {
        if (actorId <= 0 || token == null || token.isBlank()) throw invalid();
        try {
            if (token.length() > 512) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) throw invalid();
            byte[] payloadBytes = decode(parts[0]);
            byte[] suppliedMac = decode(parts[1]);
            if (suppliedMac.length != MAC_BYTES) throw invalid();
            byte[] expectedMac = sign(payloadBytes);
            if (!MessageDigest.isEqual(expectedMac, suppliedMac)) throw invalid();
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 5 || !"1".equals(fields[0])) throw invalid();
            UUID fileId = UUID.fromString(fields[1]);
            if (!fields[1].equals(fileId.toString())) throw invalid();
            long tokenActor = parsePositiveLong(fields[2]);
            if (tokenActor != actorId) throw invalid();
            long expiresEpoch = parsePositiveLong(fields[3]);
            byte[] nonce = decode(fields[4]);
            if (nonce.length != NONCE_BYTES) throw invalid();
            Instant expires = Instant.ofEpochSecond(expiresEpoch);
            if (!expires.isAfter(clock.instant())) throw invalid();
            return new Claims(fileId, tokenActor, expires, nonce.clone());
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC is unavailable", ex);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw invalid();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!encode(decoded).equals(value)) throw invalid();
        return decoded;
    }

    private static long parsePositiveLong(String value) {
        if (!value.matches("[1-9][0-9]*")) throw invalid();
        long parsed = Long.parseLong(value);
        if (parsed <= 0) throw invalid();
        return parsed;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid download token");
    }

    public record Claims(UUID fileId, long actorId, Instant expiresAt, byte[] nonce) {
        public Claims {
            if (fileId == null || actorId <= 0 || expiresAt == null || nonce == null || nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("invalid download token claims");
            }
            nonce = nonce.clone();
        }

        @Override public byte[] nonce() { return nonce.clone(); }
        public long expiresEpochSecond() { return expiresAt.getEpochSecond(); }
    }
}
