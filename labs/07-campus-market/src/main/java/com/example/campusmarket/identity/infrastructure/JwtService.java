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
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public JwtService(@Value("${campus.market.jwt.secret}") String secret,
                      @Value("${campus.market.jwt.ttl:15m}") Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    public JwtService(String secret) {
        this(secret, Duration.ofMinutes(15), Clock.systemUTC());
    }

    public JwtService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(AuthenticatedUser user) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
            .subject(user.userId().toString())
            .claim("roles", user.roles())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
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
        return ttl;
    }
}
