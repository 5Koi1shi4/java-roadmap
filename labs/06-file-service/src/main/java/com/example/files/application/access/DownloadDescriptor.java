package com.example.files.application.access;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Authorized download metadata and the already-open, prefetched content stream. */
public record DownloadDescriptor(UUID fileId, long actorId, String objectKey, String displayName,
                                 String mediaType, long size, InputStream content)
    implements AutoCloseable {
    public DownloadDescriptor {
        if (fileId == null || actorId <= 0 || objectKey == null || objectKey.isBlank()
            || displayName == null || displayName.isBlank() || mediaType == null || mediaType.isBlank()
            || size < 0 || content == null) {
            throw new IllegalArgumentException("invalid download descriptor");
        }
    }

    @Override
    public void close() throws IOException {
        content.close();
    }
}
