package com.example.files.config;

import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.access.FileAccessRepository;
import com.example.files.application.access.FileAccessService;
import com.example.files.application.access.DownloadService;
import com.example.files.application.access.LocalDownloadTokenService;
import com.example.files.application.access.DownloadTelemetry;
import com.example.files.application.access.DefaultDownloadTelemetry;
import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.application.upload.BlobRepository;
import com.example.files.application.upload.FileRepository;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StagingWaitPolicy;
import com.example.files.application.upload.UploadFailureClassifier;
import com.example.files.application.upload.UploadInspector;
import com.example.files.application.upload.UploadService;
import com.example.files.application.upload.UploadSessionRepository;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.application.upload.StagingRecoveryService;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import com.example.files.infrastructure.persistence.JdbcFileAccessRepository;
import com.example.files.infrastructure.persistence.JdbcBlobRepository;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.persistence.JdbcFileRepository;
import com.example.files.infrastructure.persistence.JdbcUploadSessionRepository;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 生产上传链路的明确 Spring 组装点。每个端口均绑定真实 JDBC、文件存储和事务实现，
 * 这样缺失基础设施时应用会在启动阶段失败，而不会由条件 Controller 隐藏配置错误。
 */
@Configuration(proxyBeanMethods = false)
@Import(ObjectStorageConfiguration.class)
public class FileServiceWiringConfiguration {

    @Bean
    public JdbcUploadSessionRepository uploadSessionRepository(JdbcTemplate jdbc) {
        return new JdbcUploadSessionRepository(jdbc);
    }

    @Bean
    public JdbcBlobRepository blobRepository(JdbcTemplate jdbc) {
        return new JdbcBlobRepository(jdbc);
    }

    @Bean
    public JdbcFileRepository fileRepository(JdbcTemplate jdbc) {
        return new JdbcFileRepository(jdbc);
    }

    @Bean
    public JdbcFileAccessRepository fileAccessRepository(JdbcTemplate jdbc) {
        return new JdbcFileAccessRepository(jdbc);
    }

    @Bean
    public JdbcCleanupTaskRepository cleanupTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        return new JdbcCleanupTaskRepository(jdbc, transactionTemplate);
    }

    @Bean
    public JdbcAuditRecorder auditRecorder(JdbcTemplate jdbc) {
        return new JdbcAuditRecorder(jdbc);
    }

    @Bean
    public DataSourceTransactionManager fileTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate fileTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public FileAccessService fileAccessService(FileAccessRepository repository, AuditRecorder audits,
                                               TransactionTemplate transactionTemplate) {
        return new FileAccessService(repository, audits, transactionTemplate);
    }

    @Bean
    @ConditionalOnExpression("'${file.download.local-hmac-secret:}'.length() >= 32")
    public LocalDownloadTokenService localDownloadTokenService(FileServiceProperties properties, Clock downloadClock) {
        return new LocalDownloadTokenService(properties.download(), downloadClock);
    }

    @Bean
    public Clock downloadClock() {
        return Clock.systemUTC();
    }

    @Bean
    public DownloadService downloadService(FileAccessRepository repository, AuditRecorder audits,
                                           ObjectStorage storage, TransactionTemplate transactionTemplate,
                                           ObjectProvider<LocalDownloadTokenService> tokens,
                                           FileServiceProperties properties, Clock downloadClock,
                                           DownloadTelemetry telemetry) {
        return new DownloadService(repository, audits, storage, transactionTemplate,
            tokens.getIfAvailable(), properties.download().maxLinkTtl(), downloadClock, telemetry);
    }

    @Bean
    public DownloadTelemetry downloadTelemetry(ObjectProvider<MeterRegistry> registry) {
        return new DefaultDownloadTelemetry(registry.getIfAvailable());
    }

    @Bean
    public UploadInspector uploadInspector(FileServiceProperties properties) {
        return new UploadInspector(properties);
    }

    @Bean
    public StagingWaitPolicy stagingWaitPolicy(FileServiceProperties properties) {
        return new StagingWaitPolicy(properties);
    }

    @Bean
    public UploadFailureClassifier uploadFailureClassifier() {
        return new UploadFailureClassifier();
    }

    @Bean
    public UploadTransactionService uploadTransactionService(UploadSessionRepository sessions,
                                                               BlobRepository blobs,
                                                               FileRepository files,
                                                               AuditRecorder audits,
                                                               TransactionTemplate transactionTemplate,
                                                               FileServiceProperties properties) {
        return new UploadTransactionService(sessions, blobs, files, audits, transactionTemplate, properties);
    }

    @Bean
    public UploadService uploadService(UploadTransactionService transactions,
                                       UploadInspector inspector,
                                       ObjectStorage storage,
                                       StagingWaitPolicy waitPolicy,
                                       CleanupTaskRepository cleanupTasks,
                                       UploadFailureClassifier classifier,
                                       FileServiceProperties properties) {
        return new UploadService(transactions, inspector, storage, waitPolicy, cleanupTasks, classifier, properties);
    }

    @Bean
    public StagingRecoveryService stagingRecoveryService(UploadSessionRepository sessions,
                                                         BlobRepository blobs,
                                                         UploadTransactionService transactions,
                                                         ObjectStorage storage,
                                                         CleanupTaskRepository cleanupTasks,
                                                         FileServiceProperties properties) {
        return new StagingRecoveryService(sessions, blobs, transactions, storage, cleanupTasks, properties);
    }
}
