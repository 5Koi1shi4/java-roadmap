package com.example.files.application.cleanup;

import com.example.files.application.upload.StagingRecoveryService;
import java.util.Objects;

/** 过期上传的唯一入口，复用 Task5 恢复逻辑，避免误删活跃临时对象。 */
public final class ExpiredUploadService {
    private final StagingRecoveryService recovery;
    private final int batchSize;
    public ExpiredUploadService(StagingRecoveryService recovery, int batchSize) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        if (batchSize <= 0 || batchSize > 50) throw new IllegalArgumentException("invalid expiration batch size");
        this.batchSize = batchSize;
    }
    public ExpiredUploadService(StagingRecoveryService recovery,
                                com.example.files.config.FileServiceProperties properties) {
        this(recovery, Objects.requireNonNull(properties, "properties").cleanup().batchSize());
    }
    public int expireUploads(String owner) { return recovery.recoverExpired(batchSize, owner); }
}
