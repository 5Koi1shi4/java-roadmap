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

    /** Creates a new receiving session using the database clock as the sole time authority. */
    public static UploadSession newSession(UUID sessionId, long uploaderId, String tempKey,
                                           UUID ownerToken, SafeDisplayName originalName,
                                           String declaredType, java.time.Duration ttl,
                                           java.time.Duration lease, Instant databaseNow) {
        require(ttl, "ttl");
        require(lease, "lease");
        require(databaseNow, "databaseNow");
        if (!positive(ttl) || !positive(lease)) {
            throw new IllegalArgumentException("ttl and lease must be positive");
        }
        return newSession(sessionId, uploaderId, tempKey, ownerToken, originalName, declaredType,
            databaseNow.plus(lease), databaseNow.plus(ttl), databaseNow);
    }

    /** Explicit-time variant for callers that already calculated values from database time. */
    public static UploadSession newSession(UUID sessionId, long uploaderId, String tempKey,
                                           UUID ownerToken, SafeDisplayName originalName,
                                           String declaredType, Instant leaseUntil, Instant expiresAt,
                                           Instant databaseNow) {
        require(leaseUntil, "leaseUntil");
        require(expiresAt, "expiresAt");
        require(databaseNow, "databaseNow");
        if (!leaseUntil.isAfter(databaseNow) || !expiresAt.isAfter(databaseNow)) {
            throw new IllegalArgumentException("leaseUntil and expiresAt must be in the future");
        }
        return new UploadSession(sessionId, uploaderId, tempKey, ownerToken,
            UploadSessionStatus.RECEIVING, leaseUntil, expiresAt,
            originalName, declaredType, null, null, null, null, null, null,
            databaseNow, databaseNow);
    }

    /** Rehydrates a row read from persistence; expired timestamps are valid persisted state. */
    public static UploadSession rehydrate(UUID sessionId, long uploaderId, String tempKey,
                                          UUID ownerToken, UploadSessionStatus status,
                                          Instant leaseUntil, Instant expiresAt,
                                          SafeDisplayName originalName, String declaredType,
                                          Long actualSize, DetectedFileType detectedType,
                                          String contentHash, Long blobId, UUID fileId,
                                          String failureCode, Instant createdAt, Instant updatedAt) {
        return new UploadSession(sessionId, uploaderId, tempKey, ownerToken, status,
            leaseUntil, expiresAt, originalName, declaredType, actualSize, detectedType,
            contentHash, blobId, fileId, failureCode, createdAt, updatedAt);
    }

    public static UploadSession begin(UUID sessionId, long uploaderId, String tempKey,
                                      UUID ownerToken, SafeDisplayName originalName,
                                      String declaredType, java.time.Duration ttl,
                                      java.time.Duration lease, Instant databaseNow) {
        return newSession(sessionId, uploaderId, tempKey, ownerToken, originalName,
            declaredType, ttl, lease, databaseNow);
    }

    private static boolean positive(java.time.Duration value) {
        return !value.isZero() && !value.isNegative();
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
