package com.example.campusmarket.unit.identity;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "test-jwt-secret-that-is-at-least-256-bits-long";

    @Test
    void rejectsConfiguredTtlThatIsNotExactlyFifteenMinutes() {
        assertThatThrownBy(() -> new JwtService(SECRET, Duration.ofMinutes(30), Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSignedTokenMissingRequiredClaims() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder().subject(UUID.randomUUID().toString())
            .signWith(key, Jwts.SIG.HS256).compact();

        JwtService service = new JwtService(SECRET);
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(RuntimeException.class);
    }
}
