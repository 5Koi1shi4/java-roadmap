package com.example.security.unit;

import com.example.security.application.InvalidTokenException;
import com.example.security.application.RefreshTokenService;
import com.example.security.domain.RefreshTokenSession;
import com.example.security.domain.RefreshTokenSessionRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private final InMemoryRefreshTokenRepository sessions = new InMemoryRefreshTokenRepository();
    private final RefreshTokenService service = new RefreshTokenService(
            sessions,
            Duration.ofDays(7),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void storesOnlyHashWhenRefreshTokenIsIssued() {
        String rawToken = service.issue(42L);

        RefreshTokenSession stored = sessions.findByHash(sha256(rawToken)).orElseThrow();
        assertThat(stored.userId()).isEqualTo(42L);
        assertThat(stored.tokenHash()).isNotEqualTo(rawToken);
        assertThat(stored.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void rotatesRefreshTokenAndRevokesPreviousSession() {
        String firstToken = service.issue(42L);

        RefreshTokenService.RotatedRefreshToken rotated = service.rotate(firstToken);

        assertThat(rotated.userId()).isEqualTo(42L);
        assertThat(rotated.rawToken()).isNotEqualTo(firstToken);
        assertThat(sessions.findByHash(sha256(firstToken)).orElseThrow().revoked()).isTrue();
        assertThat(sessions.findByHash(sha256(rotated.rawToken()))).isPresent();
    }

    @Test
    void rejectsRevokedRefreshToken() {
        String rawToken = service.issue(42L);
        service.revoke(rawToken);

        assertThatThrownBy(() -> service.rotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredRefreshToken() {
        String rawToken = service.issue(42L);
        RefreshTokenService afterExpiry = new RefreshTokenService(
                sessions,
                Duration.ofDays(7),
                Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> afterExpiry.rotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class InMemoryRefreshTokenRepository implements RefreshTokenSessionRepository {
        private final Map<String, RefreshTokenSession> sessions = new HashMap<>();

        @Override
        public void save(RefreshTokenSession session) {
            sessions.put(session.tokenHash(), session);
        }

        @Override
        public Optional<RefreshTokenSession> findByHash(String tokenHash) {
            return Optional.ofNullable(sessions.get(tokenHash));
        }

        @Override
        public boolean revokeIfUsable(String tokenHash, Instant now) {
            RefreshTokenSession current = sessions.get(tokenHash);
            if (current == null || !current.isUsableAt(now)) {
                return false;
            }
            sessions.put(tokenHash, current.revoke());
            return true;
        }
    }
}
