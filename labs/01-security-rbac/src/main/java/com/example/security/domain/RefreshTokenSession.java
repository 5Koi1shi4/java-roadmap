package com.example.security.domain;

import java.time.Instant;
import java.util.Objects;

public record RefreshTokenSession(
        String tokenHash,
        long userId,
        Instant expiresAt,
        boolean revoked
) {
    public RefreshTokenSession {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

    public RefreshTokenSession revoke() {
        return new RefreshTokenSession(tokenHash, userId, expiresAt, true);
    }

    public boolean isUsableAt(Instant instant) {
        return !revoked && instant.isBefore(expiresAt);
    }
}
