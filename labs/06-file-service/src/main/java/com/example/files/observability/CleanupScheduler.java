package com.example.files.observability;

import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.cleanup.ExpiredUploadService;
import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.UUID;

/** 外置调度周期的清理 worker；资源授权由数据库随机 token 完成。 */
public final class CleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);
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
        RuntimeException failure = null;
        try {
            if (expiredUploads != null) expiredUploads.expireUploads(owner);
        } catch (RuntimeException current) {
            log.error("过期上传清理阶段失败，继续执行任务清理阶段", current);
            failure = current;
        }
        try {
            cleanup.runBatch(owner);
        } catch (RuntimeException current) {
            log.error("存储清理任务阶段失败", current);
            if (failure == null) failure = current;
            else failure.addSuppressed(current);
        }
        if (failure != null) runFallback(failure);
        if (failure != null) throw failure;
    }

    private void runFallback(RuntimeException cause) {
        // fallback 仅由 local wiring 提供；MinIO wiring 不会装配该 bean。
        if (fallback == null || !isDatabaseFailure(cause)) return;
        try {
            fallback.clean();
        } catch (RuntimeException fallbackFailure) {
            cause.addSuppressed(fallbackFailure);
            log.error("本地临时对象兜底清理失败", fallbackFailure);
        }
    }

    private static boolean isDatabaseFailure(Throwable failure) {
        if (failure instanceof DataAccessException) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (isDatabaseFailure(suppressed)) return true;
        }
        return failure.getCause() != null && failure.getCause() != failure && isDatabaseFailure(failure.getCause());
    }
    String ownerForTests() { return owner; }
}
