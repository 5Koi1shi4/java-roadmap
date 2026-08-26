package com.example.files.application.upload;

/** Metadata returned after a bounded temporary-object write. */
public record TemporaryObject(String key, long size) {
    public TemporaryObject {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("temporary object key must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("temporary object size must not be negative");
        }
    }

    public String objectKey() {
        return key;
    }

    public long bytesWritten() {
        return size;
    }
}
