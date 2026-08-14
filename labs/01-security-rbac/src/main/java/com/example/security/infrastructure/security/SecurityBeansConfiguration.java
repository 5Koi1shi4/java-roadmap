package com.example.security.infrastructure.security;

import com.example.security.application.AuthService;
import com.example.security.application.RbacService;
import com.example.security.application.RefreshTokenService;
import com.example.security.domain.RbacRepository;
import com.example.security.domain.RefreshTokenSessionRepository;
import com.example.security.domain.UserRepository;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Configuration
public class SecurityBeansConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtTokenService jwtTokenService(
            Clock clock,
            @Value("${jwt.secret:}") String configuredSecret
    ) {
        return new JwtTokenService(signingKey(configuredSecret), Duration.ofMinutes(15), clock);
    }

    @Bean
    AuthService authService(UserRepository users, PasswordEncoder passwordEncoder) {
        return new AuthService(users, passwordEncoder);
    }

    @Bean
    RbacService rbacService(RbacRepository repository) {
        return new RbacService(repository);
    }

    @Bean
    RefreshTokenService refreshTokenService(RefreshTokenSessionRepository sessions, Clock clock) {
        return new RefreshTokenService(sessions, Duration.ofDays(7), clock);
    }

    private SecretKey signingKey(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalStateException("JWT secret must be configured");
        }
        try {
            byte[] decodedSecret = Base64.getDecoder().decode(configuredSecret);
            if (decodedSecret.length < 32) {
                throw new IllegalStateException("JWT secret must decode to at least 32 bytes");
            }
            return Keys.hmacShaKeyFor(decodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT secret must be Base64-encoded", exception);
        }
    }
}
