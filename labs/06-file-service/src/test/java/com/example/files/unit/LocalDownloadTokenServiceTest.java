package com.example.files.unit;

import com.example.files.application.access.LocalDownloadTokenService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDownloadTokenServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final UUID FILE = UUID.randomUUID();

    @Test
    void tokenIsBoundToFileActorAndExpiry() {
        LocalDownloadTokenService service = service();
        String token = service.issue(7L, FILE, Duration.ofMinutes(2));

        LocalDownloadTokenService.Claims claims = service.verify(token, 7L);
        assertThat(claims.fileId()).isEqualTo(FILE);
        assertThat(claims.actorId()).isEqualTo(7L);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(120));

        assertThatThrownBy(() -> service.verify(token, 8L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenRejectsTamperingAndExpiry() {
        LocalDownloadTokenService service = service();
        String token = service.issue(7L, FILE, Duration.ofSeconds(30));
        String tampered = token.substring(0, token.length() - 1)
            + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.verify(tampered, 7L))
            .isInstanceOf(IllegalArgumentException.class);

        LocalDownloadTokenService expired = new LocalDownloadTokenService(
            SECRET, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.verify(token, 7L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWeakSecretAndUnsafeTtl() {
        assertThatThrownBy(() -> new LocalDownloadTokenService("short"))
            .isInstanceOf(IllegalArgumentException.class);
        LocalDownloadTokenService service = service();
        assertThatThrownBy(() -> service.issue(7L, FILE, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(7L, FILE, Duration.ofSeconds(121)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(7L, FILE, Duration.ofMillis(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalBase64Parts() {
        LocalDownloadTokenService service = service();
        String token = service.issue(7L, FILE, Duration.ofSeconds(30));
        String[] parts = token.split("\\.");
        // 即使解码字节相同，也不得接受非规范的末尾 Base64 字符。
        String nonCanonicalPayload = parts[0] + "A";
        assertThatThrownBy(() -> service.verify(nonCanonicalPayload + "." + parts[1], 7L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static LocalDownloadTokenService service() {
        return new LocalDownloadTokenService(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
