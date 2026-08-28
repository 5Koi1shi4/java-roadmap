package com.example.files.observability;

import com.example.files.application.cleanup.ExpiredUploadService;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import com.example.files.application.audit.FileServiceMetrics;
import com.example.files.config.FileServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 上传恢复与存储清理的定时入口。两个阶段各自隔离异常，恢复阶段故障不能饿死清理阶段。
 * 调度周期来自类型安全配置，数据库租约和过期判断仍由各应用服务负责。
 */
public final class UploadRecoveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(UploadRecoveryScheduler.class);
    private final ExpiredUploadService expiredUploads;
    private final StorageCleanupService cleanup;
    private final FileServiceMetrics metrics;
    private final LocalTemporaryFallbackCleaner fallback;
    private final String owner = "recovery-worker-" + UUID.randomUUID();

    public UploadRecoveryScheduler(ExpiredUploadService expiredUploads, StorageCleanupService cleanup,
                                   FileServiceProperties properties, FileServiceMetrics metrics) {
        this(expiredUploads, cleanup, properties, metrics, null);
    }

    public UploadRecoveryScheduler(ExpiredUploadService expiredUploads, StorageCleanupService cleanup,
                                   FileServiceProperties properties, FileServiceMetrics metrics,
                                   LocalTemporaryFallbackCleaner fallback) {
        this.expiredUploads = Objects.requireNonNull(expiredUploads, "expiredUploads");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(properties, "properties");
        this.metrics = metrics;
        this.fallback = fallback;
    }

    public UploadRecoveryScheduler(ExpiredUploadService expiredUploads, StorageCleanupService cleanup,
                                   FileServiceProperties properties) {
        this(expiredUploads, cleanup, properties, null);
    }

    public UploadRecoveryScheduler(ExpiredUploadService expiredUploads, StorageCleanupService cleanup,
                                   int ignoredBatchSize, Duration ignoredSchedule, FileServiceMetrics metrics) {
        this.expiredUploads = Objects.requireNonNull(expiredUploads, "expiredUploads");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        if (ignoredBatchSize <= 0 || ignoredBatchSize > 50 || ignoredSchedule == null || ignoredSchedule.isNegative()
            || ignoredSchedule.isZero()) throw new IllegalArgumentException("invalid recovery scheduler configuration");
        this.metrics = metrics;
        this.fallback = null;
    }

    @Scheduled(fixedDelayString = "${file.cleanup.schedule}")
    public void run() {
        RuntimeException failure = null;
        try {
            expiredUploads.expireUploads(owner);
        } catch (RuntimeException ex) {
            failure = ex;
            LOG.error("上传恢复阶段失败，继续执行对象清理阶段", ex);
        }
        try {
            cleanup.runBatch(owner);
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
            LOG.error("对象清理阶段失败", ex);
        }
        if (failure != null && fallback != null && isDatabaseFailure(failure)) {
            try { fallback.clean(); }
            catch (RuntimeException fallbackFailure) { failure.addSuppressed(fallbackFailure); }
        }
        if (failure != null) throw failure;
    }

    String ownerForTests() { return owner; }

    private static boolean isDatabaseFailure(Throwable failure) {
        if (failure instanceof DataAccessException) return true;
        for (Throwable suppressed : failure.getSuppressed()) if (isDatabaseFailure(suppressed)) return true;
        return failure.getCause() != null && failure.getCause() != failure && isDatabaseFailure(failure.getCause());
    }
}
