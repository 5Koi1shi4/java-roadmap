package com.example.files.config;

import com.example.files.application.cleanup.*;
import com.example.files.application.upload.*;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 清理调度仅在明确启用时装配，防止默认路由和后台任务意外暴露。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "file.maintenance", name = "enabled", havingValue = "true")
public class CleanupMaintenanceConfiguration {
    @Bean public StorageCleanupService storageCleanupService(JdbcCleanupTaskRepository tasks, ObjectStorage storage,
            UploadSessionRepository sessions, BlobRepository blobs, FileServiceProperties properties) {
        return new StorageCleanupService(tasks, storage, sessions, blobs, properties);
    }
    @Bean public ExpiredUploadService expiredUploadService(com.example.files.application.upload.StagingRecoveryService recovery,
            FileServiceProperties properties) { return new ExpiredUploadService(recovery, properties); }
    @Bean public com.example.files.observability.CleanupScheduler cleanupScheduler(StorageCleanupService service) {
        return new com.example.files.observability.CleanupScheduler(service);
    }
}
