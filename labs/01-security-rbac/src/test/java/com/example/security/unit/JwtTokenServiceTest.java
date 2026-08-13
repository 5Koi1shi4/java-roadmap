package com.example.security.unit;

import com.example.security.application.InvalidTokenException;
import com.example.security.domain.User;
import com.example.security.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T14:00:00Z");
    private static final SecretKeySpec KEY = new SecretKeySpec(
            "test-key-must-be-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
    );

    @Test
    void verifiesSignedAccessTokenAndReturnsItsSubject() {
        JwtTokenService tokens = tokenService();
        User user = new User(42L, "student", "password-hash", true);

        String accessToken = tokens.issueAccessToken(user);

        JwtTokenService.AccessPrincipal principal = tokens.verifyAccessToken(accessToken);
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.username()).isEqualTo("student");
    }

    @Test
    void rejectsRefreshTokenWhenUsedAsAccessToken() {
        JwtTokenService tokens = tokenService();

        String refreshToken = tokens.issueRefreshToken();

        assertThatThrownBy(() -> tokens.verifyAccessToken(refreshToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAccessTokenAfterItsExpiry() {
        User user = new User(42L, "student", "password-hash", true);
        JwtTokenService issuer = tokenService();
        String accessToken = issuer.issueAccessToken(user);
        Clock afterExpiry = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        JwtTokenService verifier = new JwtTokenService(KEY, Duration.ofMinutes(15), afterExpiry);

        assertThatThrownBy(() -> verifier.verifyAccessToken(accessToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    private JwtTokenService tokenService() {
        return new JwtTokenService(
                KEY,
                Duration.ofMinutes(15),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
