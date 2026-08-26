package com.example.files.application.upload;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Storage boundary used by the upload workflow. */
public interface ObjectStorage {
    TemporaryObject writeTemporary(String tempKey, InputStream source, long maxBytes);

    void commit(String tempKey, String objectKey);

    InputStream open(String objectKey);

    StorageObjectMetadata stat(String objectKey);

    void delete(String objectKey);

    Optional<URI> createPresignedGet(String objectKey, Duration ttl,
                                     Map<String, String> responseHeaders);
}
