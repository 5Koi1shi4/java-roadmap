package com.example.files.application.access;

import java.time.Instant;
import java.util.UUID;

/** 授权后的逻辑文件元数据；不携带物理 Blob 标识、Key 或哈希。 */
public record FileView(UUID fileId, long ownerId, String displayName,
                       String mediaType, long size, Instant createdAt) {
    public FileView {
        if (fileId == null || ownerId <= 0 || displayName == null || displayName.isBlank()
            || mediaType == null || mediaType.isBlank() || size < 0 || createdAt == null) {
            throw new IllegalArgumentException("invalid file view");
        }
    }
}
