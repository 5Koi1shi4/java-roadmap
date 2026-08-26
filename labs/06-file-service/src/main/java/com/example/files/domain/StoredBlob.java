package com.example.files.domain;

import java.time.Instant;
import java.util.UUID;

/** Mutable domain aggregate whose transitions are deliberately narrower than the persistence model. */
public final class StoredBlob {
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;
    private final long id;
    private final String contentHash;
    private String objectKey;
    private final long sizeBytes;
    private final DetectedFileType mediaType;
    private long referenceCount;
    private BlobStatus status;
    private long generation;
    private UUID stagingSessionId;
    private UUID stagingOwnerToken;
    private Instant stagingLeaseUntil;
    private UUID cleanupToken;
    private Instant cleanupLeaseUntil;
    private final Instant createdAt;
    private Instant updatedAt;

    private StoredBlob(long id, String contentHash, String objectKey, long sizeBytes,
                       DetectedFileType mediaType, long generation, UUID stagingSessionId,
                       UUID stagingOwnerToken, Instant stagingLeaseUntil, Instant databaseNow) {
        if (id <= 0 || contentHash == null || !contentHash.matches("[0-9a-fA-F]{64}")
            || objectKey == null || objectKey.isBlank() || objectKey.contains("..")
            || objectKey.contains("\\") || objectKey.startsWith("/") || sizeBytes < 0
            || mediaType == null || generation <= 0 || stagingSessionId == null
            || stagingOwnerToken == null || stagingLeaseUntil == null || databaseNow == null) {
            throw new IllegalArgumentException("invalid blob metadata");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("sizeBytes must not exceed 20 MiB");
        }
        if (!stagingLeaseUntil.isAfter(databaseNow)) {
            throw new IllegalArgumentException("staging lease must be in the future");
        }
        this.id = id;
        this.contentHash = contentHash.toLowerCase(java.util.Locale.ROOT);
        this.objectKey = objectKey;
        this.sizeBytes = sizeBytes;
        this.mediaType = mediaType;
        this.referenceCount = 0L;
        this.status = BlobStatus.STAGING;
        this.generation = generation;
        this.stagingSessionId = stagingSessionId;
        this.stagingOwnerToken = stagingOwnerToken;
        this.stagingLeaseUntil = stagingLeaseUntil;
        this.createdAt = databaseNow;
        this.updatedAt = this.createdAt;
    }

    public static StoredBlob beginStaging(long id, String contentHash, String objectKey, long sizeBytes,
                                          DetectedFileType mediaType, long generation,
                                          UUID stagingSessionId, UUID stagingOwnerToken,
                                          Instant stagingLeaseUntil, Instant databaseNow) {
        return new StoredBlob(id, contentHash, objectKey, sizeBytes, mediaType, generation,
            stagingSessionId, stagingOwnerToken, stagingLeaseUntil, databaseNow);
    }

    public StoredBlob ready() {
        transition(BlobStatus.STAGING, BlobStatus.READY);
        this.stagingSessionId = null;
        this.stagingOwnerToken = null;
        this.stagingLeaseUntil = null;
        return this;
    }

    /** Moves an unreferenced READY blob into the cleanup queue. */
    public StoredBlob beginDeleting() {
        if (status != BlobStatus.READY || referenceCount != 0) {
            throw new IllegalStateException("only an unreferenced READY blob can be pending deletion");
        }
        status = BlobStatus.PENDING_DELETE;
        return this;
    }

    public StoredBlob beginDeletionAttempt(UUID cleanupToken, Instant cleanupLeaseUntil, Instant databaseNow) {
        if (status != BlobStatus.PENDING_DELETE || cleanupToken == null || cleanupLeaseUntil == null
            || databaseNow == null || !cleanupLeaseUntil.isAfter(databaseNow)) {
            throw new IllegalStateException("invalid deletion claim");
        }
        this.cleanupToken = cleanupToken;
        this.cleanupLeaseUntil = cleanupLeaseUntil;
        status = BlobStatus.DELETING;
        return this;
    }

    public StoredBlob deleted() {
        transition(BlobStatus.DELETING, BlobStatus.DELETED);
        this.cleanupToken = null;
        this.cleanupLeaseUntil = null;
        return this;
    }

    /** Reuses metadata only after the old physical object has reached DELETED. */
    public StoredBlob beginStaging(String newObjectKey, UUID newSessionId, UUID newOwnerToken,
                                   Instant newLeaseUntil, Instant databaseNow) {
        if (status != BlobStatus.DELETED || newSessionId == null || newOwnerToken == null
            || newLeaseUntil == null || databaseNow == null || !newLeaseUntil.isAfter(databaseNow)
            || newObjectKey == null || newObjectKey.isBlank() || newObjectKey.contains("..")
            || newObjectKey.contains("\\") || newObjectKey.startsWith("/")
            || newObjectKey.equals(objectKey)) {
            throw new IllegalArgumentException("restaging requires a different safe object key");
        }
        objectKey = newObjectKey;
        status = BlobStatus.STAGING;
        generation++;
        referenceCount = 0;
        stagingSessionId = newSessionId;
        stagingOwnerToken = newOwnerToken;
        stagingLeaseUntil = newLeaseUntil;
        cleanupToken = null;
        cleanupLeaseUntil = null;
        return this;
    }

    /** Adds one logical file reference; only published blobs may be referenced. */
    public long addReference() {
        if (status != BlobStatus.READY) {
            throw new IllegalStateException("only a READY blob can gain references");
        }
        referenceCount++;
        return referenceCount;
    }

    /** Removes one reference and emits a result when this was the final reference. */
    public ReferenceBecameZero removeReference() {
        if (status != BlobStatus.READY || referenceCount <= 0) {
            throw new IllegalStateException("blob has no removable reference");
        }
        referenceCount--;
        if (referenceCount == 0) {
            status = BlobStatus.PENDING_DELETE;
            return new ReferenceBecameZero(id, objectKey, generation);
        }
        return null;
    }

    // Names used by persistence/application code are kept explicit aliases of the domain operations.
    public long incrementReference() { return addReference(); }
    public ReferenceBecameZero decrementReference() { return removeReference(); }

    private void transition(BlobStatus expected, BlobStatus next) {
        if (status != expected) {
            throw new IllegalStateException("invalid blob transition " + status + " -> " + next);
        }
        status = next;
    }

    public long id() { return id; }
    public long blobId() { return id; }
    public String contentHash() { return contentHash; }
    public String objectKey() { return objectKey; }
    public long sizeBytes() { return sizeBytes; }
    public long size() { return sizeBytes; }
    public DetectedFileType mediaType() { return mediaType; }
    public DetectedFileType detectedType() { return mediaType; }
    public String mediaTypeValue() { return mediaType.mediaType(); }
    public long referenceCount() { return referenceCount; }
    public BlobStatus status() { return status; }
    public long generation() { return generation; }
    public UUID stagingSessionId() { return stagingSessionId; }
    public UUID stagingOwnerToken() { return stagingOwnerToken; }
    public Instant stagingLeaseUntil() { return stagingLeaseUntil; }
    public UUID cleanupToken() { return cleanupToken; }
    public Instant cleanupLeaseUntil() { return cleanupLeaseUntil; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
