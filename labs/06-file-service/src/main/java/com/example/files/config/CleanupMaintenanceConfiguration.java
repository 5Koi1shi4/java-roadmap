package com.example.files.config;

import com.example.files.application.cleanup.*;
import com.example.files.application.upload.*;
import com.example.files.application.audit.FileServiceMetrics;
import com.example.files.api.CleanupMaintenanceController;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 清理调度仅在明确启用时装配，防止默认路由和后台任务意外暴露。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "file.maintenance", name = "enabled", havingValue = "true")
public class CleanupMaintenanceConfiguration {
    @Bean public StorageCleanupService storageCleanupService(JdbcCleanupTaskRepository tasks, ObjectStorage storage,
            UploadSessionRepository sessions, BlobRepository blobs, FileServiceProperties properties,
            org.springframework.beans.factory.ObjectProvider<FileServiceMetrics> metrics) {
        return new StorageCleanupService(tasks, storage, sessions, blobs, properties, metrics.getIfAvailable());
    }
    @Bean public ExpiredUploadService expiredUploadService(com.example.files.application.upload.StagingRecoveryService recovery,
            FileServiceProperties properties) { return new ExpiredUploadService(recovery, properties); }
    @Bean public com.example.files.observability.CleanupScheduler cleanupScheduler(StorageCleanupService service,
            ExpiredUploadService expiredUploadService,
            org.springframework.beans.factory.ObjectProvider<LocalTemporaryFallbackCleaner> fallback) {
        return new com.example.files.observability.CleanupScheduler(service, expiredUploadService, fallback.getIfAvailable());
    }
    @Bean public com.example.files.observability.UploadRecoveryScheduler uploadRecoveryScheduler(
            StorageCleanupService service, ExpiredUploadService expiredUploadService,
            FileServiceProperties properties, org.springframework.beans.factory.ObjectProvider<FileServiceMetrics> metrics,
            org.springframework.beans.factory.ObjectProvider<LocalTemporaryFallbackCleaner> fallback) {
        return new com.example.files.observability.UploadRecoveryScheduler(expiredUploadService,
            service, properties, metrics.getIfAvailable(), fallback.getIfAvailable());
    }

    /** 身份解析器存在时才注册维护路由，避免受限 profile 暴露匿名端点。 */
    @Bean
    @Profile({"local", "test"})
    @ConditionalOnProperty(prefix = "file.identity", name = "trusted-header-enabled", havingValue = "true")
    public CleanupMaintenanceController cleanupMaintenanceController(JdbcCleanupTaskRepository tasks,
                                                                       RequesterIdentityResolver identities) {
        return new CleanupMaintenanceController(tasks, identities);
    }
}
