package com.example.files.application.upload;

import com.example.files.application.audit.CorrelationId;

import java.io.InputStream;
import java.util.Objects;

/** 已由协议层解析的上传用例参数。 */
public record UploadCommand(long actorId, String originalName, String declaredType,
                            long declaredSize, InputStream body, CorrelationId correlationId) {
    public UploadCommand {
        if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
        if (originalName == null || originalName.isBlank()) throw new IllegalArgumentException("originalName is required");
        if (declaredType == null || declaredType.isBlank()) throw new IllegalArgumentException("declaredType is required");
        if (declaredSize < 0) throw new IllegalArgumentException("declaredSize must not be negative");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
