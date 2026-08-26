package com.example.files.domain;

import java.time.Instant;
import java.util.UUID;

public record StoredFile(
    UUID fileId,
    long ownerId,
    long blobId,
    SafeDisplayName displayName,
    StoredFileStatus status,
    Instant createdAt,
    Instant deletedAt) {

    public StoredFile {
        if (fileId == null || displayName == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("file metadata must not contain null values");
        }
        if (ownerId <= 0 || blobId <= 0) {
            throw new IllegalArgumentException("ownerId and blobId must be positive");
        }
    }
}
