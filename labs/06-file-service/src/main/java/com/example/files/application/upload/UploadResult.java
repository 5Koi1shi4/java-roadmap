package com.example.files.application.upload;

import java.time.Instant;
import java.util.UUID;

/** 上传终结后对外暴露的逻辑文件结果，不包含物理 Blob 信息。 */
public record UploadResult(UUID fileId, String displayName, String mediaType,
                           long size, Instant createdAt) {
    public UploadResult {
        if (fileId == null || displayName == null || displayName.isBlank()
            || mediaType == null || mediaType.isBlank() || size < 0 || createdAt == null) {
            throw new IllegalArgumentException("Upload result contains invalid values");
        }
    }
}
