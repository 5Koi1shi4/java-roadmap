package com.example.files.domain;

import java.time.Instant;
import java.util.UUID;

public record UploadSession(
    UUID sessionId,
    long uploaderId,
    String tempKey,
    UUID ownerToken,
    UploadSessionStatus status,
    Instant leaseUntil,
    Instant expiresAt,
    SafeDisplayName originalName,
    String declaredType,
    Long actualSize,
    DetectedFileType detectedType,
    String contentHash,
    Long blobId,
    UUID fileId,
    String failureCode,
    Instant createdAt,
    Instant updatedAt) {

    public UploadSession {
        require(sessionId, "sessionId");
        require(ownerToken, "ownerToken");
        require(status, "status");
        require(leaseUntil, "leaseUntil");
        require(expiresAt, "expiresAt");
        require(originalName, "originalName");
        require(createdAt, "createdAt");
        require(updatedAt, "updatedAt");
        if (uploaderId <= 0) {
            throw new IllegalArgumentException("uploaderId must be positive");
        }
        if (tempKey == null || tempKey.isBlank() || tempKey.contains("..")
            || tempKey.contains("\\") || tempKey.startsWith("/")) {
            throw new IllegalArgumentException("tempKey must be a safe non-blank key");
        }
        if (actualSize != null && actualSize < 0) {
            throw new IllegalArgumentException("actualSize must not be negative");
        }
        if (blobId != null && blobId <= 0) {
            throw new IllegalArgumentException("blobId must be positive");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
