package com.example.files.application.upload;

import java.util.Objects;
import java.util.UUID;

/** Blob 领取结果，区分新建、继续持有和复用 READY Blob。 */
public record BlobReservation(UUID sessionId, UUID ownerToken, long blobId,
                              String objectKey, Mode mode) {
    public BlobReservation {
        if (mode == null) {
            throw new IllegalArgumentException("Blob reservation contains invalid values");
        }
        if (mode == Mode.WAITING) {
            if (sessionId != null || ownerToken != null || blobId != 0 || objectKey != null) {
                throw new IllegalArgumentException("WAITING reservation must not expose Blob capability");
            }
        } else if (sessionId == null || ownerToken == null || blobId <= 0
            || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Blob reservation contains invalid values");
        }
        Objects.requireNonNull(mode);
    }

    public static BlobReservation waiting() {
        return new BlobReservation(null, null, 0L, null, Mode.WAITING);
    }

    public enum Mode { NEW_STAGING, OWNED_STAGING, WAITING, REUSE_READY }

    public boolean reusesReadyBlob() {
        return mode == Mode.REUSE_READY;
    }
}
