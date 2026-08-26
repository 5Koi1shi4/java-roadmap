package com.example.files.unit;

import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSession;
import com.example.files.domain.UploadSessionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadSessionTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void newSessionRejectsAlreadyExpiredLeaseAndExpiry() {
        assertThatThrownBy(() -> UploadSession.newSession(UUID.randomUUID(), 7L, "tmp/random",
            UUID.randomUUID(), SafeDisplayName.from("report.pdf"), "application/pdf",
            NOW.minusSeconds(1), NOW.minusSeconds(1), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistenceRehydrateCanRepresentAnExpiredSession() {
        UploadSession session = UploadSession.rehydrate(UUID.randomUUID(), 7L, "tmp/random", UUID.randomUUID(),
            UploadSessionStatus.EXPIRED, NOW.minusSeconds(60), NOW.minusSeconds(1),
            SafeDisplayName.from("report.pdf"), "application/pdf", 12L, null, null, null, null,
            null, NOW.minusSeconds(120), NOW.minusSeconds(1));
        assertThat(session.status()).isEqualTo(UploadSessionStatus.EXPIRED);
    }
}
