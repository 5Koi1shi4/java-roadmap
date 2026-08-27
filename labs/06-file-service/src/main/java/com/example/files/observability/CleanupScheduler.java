package com.example.files.observability;

import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.cleanup.ExpiredUploadService;
import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/** 外置调度周期的清理 worker；资源授权由数据库随机 token 完成。 */
public final class CleanupScheduler {
    private final StorageCleanupService cleanup;
    private final ExpiredUploadService expiredUploads;
    private final LocalTemporaryFallbackCleaner fallback;
    private final String owner = "cleanup-worker-" + UUID.randomUUID();
    public CleanupScheduler(StorageCleanupService cleanup) {
        this(cleanup, null);
    }
    public CleanupScheduler(StorageCleanupService cleanup, ExpiredUploadService expiredUploads) {
        this(cleanup, expiredUploads, null);
    }
    public CleanupScheduler(StorageCleanupService cleanup, ExpiredUploadService expiredUploads,
                            LocalTemporaryFallbackCleaner fallback) {
        this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
        this.expiredUploads = expiredUploads;
        this.fallback = fallback;
    }
    @Scheduled(fixedDelayString = "${file.cleanup.schedule}")
    public void run() {
        try {
            if (expiredUploads != null) expiredUploads.expireUploads(owner);
            cleanup.runBatch(owner);
        } catch (RuntimeException failure) {
            // 数据库/主清理不可用时只允许 local tmp 兜底；MinIO profile 不装配该 bean。
            if (fallback != null) fallback.clean();
            throw failure;
        }
    }
    String ownerForTests() { return owner; }
}
