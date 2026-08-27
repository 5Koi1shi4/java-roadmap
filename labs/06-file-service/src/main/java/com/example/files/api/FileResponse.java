package com.example.files.api;

import com.example.files.application.upload.UploadResult;

import java.time.Instant;
import java.util.UUID;

/** 上传成功响应，仅暴露逻辑文件字段。 */
public record FileResponse(UUID fileId, String displayName, String mediaType,
                           long size, Instant createdAt) {
    public FileResponse {
        if (fileId == null || displayName == null || displayName.isBlank()
            || mediaType == null || mediaType.isBlank() || size < 0 || createdAt == null) {
            throw new IllegalArgumentException("invalid file response");
        }
    }

    public static FileResponse from(UploadResult result) {
        if (result == null) throw new IllegalArgumentException("upload result is required");
        return new FileResponse(result.fileId(), result.displayName(), result.mediaType(),
            result.size(), result.createdAt());
    }
}
