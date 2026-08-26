package com.example.files.infrastructure.storage;

import java.util.UUID;

/** Generates opaque keys with the only two namespaces accepted by local storage. */
public final class SecureStorageKeyFactory {
    public SecureStorageKeyFactory() { }

    public static String temporaryKey() {
        return "tmp/" + UUID.randomUUID();
    }

    public static String objectKey() {
        return "blobs/" + UUID.randomUUID();
    }

    public static String newTemporaryKey() { return temporaryKey(); }
    public static String newObjectKey() { return objectKey(); }
}
