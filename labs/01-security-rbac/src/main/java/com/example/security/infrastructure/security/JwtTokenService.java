package com.example.security.infrastructure.security;

import com.example.security.application.InvalidTokenException;
import com.example.security.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;

public final class JwtTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey signingKey;
    private final Duration accessTokenLifetime;
    private final Clock clock;

    public JwtTokenService(SecretKey signingKey, Duration accessTokenLifetime, Clock clock) {
        this.signingKey = Objects.requireNonNull(signingKey);
        this.accessTokenLifetime = Objects.requireNonNull(accessTokenLifetime);
        this.clock = Objects.requireNonNull(clock);
    }

    public String issueAccessToken(User user) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .subject(Long.toString(user.id()))
                .claim("username", user.username())
                .claim("token_use", "access")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(accessTokenLifetime)))
                .signWith(signingKey)
                .compact();
    }

    public String issueRefreshToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public AccessPrincipal verifyAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!"access".equals(claims.get("token_use", String.class))) {
                throw new IllegalArgumentException("token is not an access token");
            }
            return new AccessPrincipal(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class)
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidTokenException(exception);
        }
    }

    public record AccessPrincipal(long userId, String username) {
    }
}
