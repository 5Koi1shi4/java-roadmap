package com.example.campusmarket.identity.infrastructure;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final Clock clock;

    private static final Duration TTL = Duration.ofMinutes(15);

    @Autowired
    public JwtService(@Value("${campus.market.jwt.secret}") String secret) {
        this(secret, TTL, Clock.systemUTC());
    }

    public JwtService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits");
        }
        if (!TTL.equals(ttl)) {
            throw new IllegalArgumentException("JWT TTL is fixed at 15 minutes");
        }
        if (clock == null) {
            throw new IllegalArgumentException("JWT clock is required");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String issue(AuthenticatedUser user) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
            .subject(user.userId().toString())
            .claim("roles", user.roles())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plus(TTL)))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    public Claims parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()
            || claims.getIssuedAt() == null || claims.getExpiration() == null
            || claims.get("roles") == null) {
            throw new IllegalArgumentException("JWT is missing required claims");
        }
        Object roles = claims.get("roles");
        if (!(roles instanceof Iterable<?> iterable) || !iterable.iterator().hasNext()) {
            throw new IllegalArgumentException("JWT roles claim is invalid");
        }
        Duration lifetime = Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
        if (!TTL.equals(lifetime)) {
            throw new IllegalArgumentException("JWT lifetime must be exactly 15 minutes");
        }
        return claims;
    }

    public AuthenticatedUser authenticate(String token) {
        Claims claims = parse(token);
        UUID userId = UUID.fromString(claims.getSubject());
        Set<String> roles = new LinkedHashSet<>();
        Object rawRoles = claims.get("roles");
        if (rawRoles instanceof Iterable<?> iterable) {
            for (Object role : iterable) {
                roles.add(String.valueOf(role));
            }
        } else if (rawRoles instanceof String role) {
            roles.add(role);
        }
        return new AuthenticatedUser(userId, roles);
    }

    public Duration ttl() {
        return TTL;
    }
}
