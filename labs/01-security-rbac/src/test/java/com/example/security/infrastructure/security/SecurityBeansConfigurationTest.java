package com.example.security.infrastructure.security;

import com.example.security.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityBeansConfigurationTest {

    @Test
    void usesConfiguredJwtSecretToVerifyTokensAcrossApplicationInstances() {
        String configuredSecret = Base64.getEncoder().encodeToString(new byte[32]);
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        SecurityBeansConfiguration configuration = new SecurityBeansConfiguration();
        User user = new User(1L, "admin", "unused", true);

        String accessToken = configuration.jwtTokenService(clock, configuredSecret).issueAccessToken(user);

        JwtTokenService.AccessPrincipal principal = configuration
                .jwtTokenService(clock, configuredSecret)
                .verifyAccessToken(accessToken);

        assertThat(principal.userId()).isEqualTo(1L);
    }

    @Test
    void rejectsMissingJwtSecret() {
        SecurityBeansConfiguration configuration = new SecurityBeansConfiguration();
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> configuration.jwtTokenService(clock, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret");
    }
}
