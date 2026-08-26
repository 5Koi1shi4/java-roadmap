package com.example.files.domain;

/** Domain result emitted when the final logical file reference is removed. */
public record ReferenceBecameZero(long blobId, String objectKey, long generation) {

    public ReferenceBecameZero {
        if (blobId <= 0) {
            throw new IllegalArgumentException("blobId must be positive");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }
}
