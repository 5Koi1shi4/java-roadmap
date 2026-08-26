package com.example.files.unit;

import com.example.files.application.upload.BlobReservation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证领取结果的类型边界不会通过哨兵值泄露等待态能力。 */
class BlobReservationTest {
    @Test
    void waitingHasNoCapabilityAndGrantedTypesCarryOnlyValidatedFields() {
        BlobReservation waiting = BlobReservation.waiting();

        assertThat(waiting).isInstanceOf(BlobReservation.Waiting.class);
        assertThat(waiting).isNotInstanceOf(BlobReservation.Granted.class);
        assertThat(waiting.mode()).isEqualTo(BlobReservation.Mode.WAITING);

        BlobReservation.Granted owned = BlobReservation.newStaging(
            UUID.randomUUID(), UUID.randomUUID(), 1L, "blobs/key");
        assertThat(owned.blobId()).isOne();
        assertThat(owned.objectKey()).isEqualTo("blobs/key");
    }

    @Test
    void ownedAndReadyReuseRejectInvalidCapabilityValues() {
        UUID sessionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new BlobReservation.Owned(sessionId, token, 0L, "blobs/key", BlobReservation.Mode.NEW_STAGING))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new BlobReservation.ReadyReuse(sessionId, token, 2L, " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
