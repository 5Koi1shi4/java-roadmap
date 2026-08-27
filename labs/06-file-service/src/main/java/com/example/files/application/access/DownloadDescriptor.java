package com.example.files.application.access;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** 已授权下载元数据及已打开且预读首段的内容流。 */
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
