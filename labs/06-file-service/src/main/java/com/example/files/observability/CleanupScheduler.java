package com.example.files.observability;

import com.example.files.application.cleanup.StorageCleanupService;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/** 外置调度周期的清理 worker；资源授权由数据库随机 token 完成。 */
public final class CleanupScheduler {
    private final StorageCleanupService cleanup;
    private final String owner = "cleanup-worker-" + UUID.randomUUID();
    public CleanupScheduler(StorageCleanupService cleanup) { this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup"); }
    @Scheduled(fixedDelayString = "${file.cleanup.schedule:60000}")
    public void run() { cleanup.runBatch(owner); }
    String ownerForTests() { return owner; }
}
