package com.example.files.application.access;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
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
        content = new IdempotentCloseInputStream(content);
    }

    @Override
    public void close() throws IOException {
        content.close();
    }

    private static final class IdempotentCloseInputStream extends FilterInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        private IdempotentCloseInputStream(InputStream input) { super(input); }

        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) super.close();
        }
    }
}
