package com.example.security.application;

import com.example.security.domain.RefreshTokenSession;
import com.example.security.domain.RefreshTokenSessionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenSessionRepository sessions;
    private final Duration lifetime;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenSessionRepository sessions,
            Duration lifetime,
            Clock clock
    ) {
        this.sessions = Objects.requireNonNull(sessions);
        this.lifetime = Objects.requireNonNull(lifetime);
        this.clock = Objects.requireNonNull(clock);
    }

    public String issue(long userId) {
        String rawToken = newRawToken();
        sessions.save(new RefreshTokenSession(
                sha256(rawToken),
                userId,
                clock.instant().plus(lifetime),
                false
        ));
        return rawToken;
    }

    public RotatedRefreshToken rotate(String rawToken) {
        String tokenHash = sha256(rawToken);
        RefreshTokenSession current = sessions.findByHash(tokenHash)
                .filter(session -> session.isUsableAt(clock.instant()))
                .orElseThrow(() -> new InvalidTokenException(
                        new IllegalArgumentException("refresh token is invalid, expired, or revoked")
                ));

        sessions.save(current.revoke());
        return new RotatedRefreshToken(current.userId(), issue(current.userId()));
    }

    public void revoke(String rawToken) {
        String tokenHash = sha256(rawToken);
        sessions.findByHash(tokenHash)
                .ifPresent(session -> sessions.save(session.revoke()));
    }

    private String newRawToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RotatedRefreshToken(long userId, String rawToken) {
    }
}
