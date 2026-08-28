package com.example.files.unit;

import com.example.files.api.FileController;
import com.example.files.api.DownloadController;
import com.example.files.api.ApiExceptionHandler;
import com.example.files.api.CorrelationIdFilter;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.BlobRepository;
import com.example.files.application.upload.UploadSessionRepository;
import com.example.files.application.upload.StagingWaitPolicy;
import com.example.files.application.upload.UploadInspector;
import com.example.files.application.upload.UploadService;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.application.upload.StagingRecoveryService;
import com.example.files.config.FileServiceProperties;
import com.example.files.config.FileServiceWiringConfiguration;
import com.example.files.config.LocalFallbackConfiguration;
import com.example.files.config.CleanupMaintenanceConfiguration;
import com.example.files.config.TrustedHeaderIdentityConfiguration;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import com.example.files.infrastructure.persistence.JdbcBlobRepository;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.persistence.JdbcFileRepository;
import com.example.files.infrastructure.persistence.JdbcUploadSessionRepository;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import com.example.files.observability.CleanupScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileServiceWiringConfigurationTest {
    @Test
    void createsRealUploadGraphAndControllerFromSpringContext() {
        DataSource dataSource = mock(DataSource.class);
        new ApplicationContextRunner()
            .withUserConfiguration(FileServiceWiringConfiguration.class, TrustedHeaderIdentityConfiguration.class,
                FileController.class, DownloadController.class, ApiExceptionHandler.class, CorrelationIdFilter.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("spring.profiles.active=test", "file.identity.trusted-header-enabled=true")
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(JdbcUploadSessionRepository.class);
                assertThat(context).hasSingleBean(JdbcBlobRepository.class);
                assertThat(context).hasSingleBean(JdbcFileRepository.class);
                assertThat(context).hasSingleBean(JdbcCleanupTaskRepository.class);
                assertThat(context).hasSingleBean(JdbcAuditRecorder.class);
                assertThat(context).hasSingleBean(UploadInspector.class);
                assertThat(context).hasSingleBean(ObjectStorage.class);
                assertThat(context).hasSingleBean(LocalObjectStorage.class);
                assertThat(context.getBean(ObjectStorage.class)).isNotNull();
                assertThat(context).hasSingleBean(StagingWaitPolicy.class);
                assertThat(context).hasSingleBean(UploadTransactionService.class);
                assertThat(context).hasSingleBean(UploadService.class);
                assertThat(context).hasSingleBean(StagingRecoveryService.class);
                assertThat(context).hasSingleBean(RequesterIdentityResolver.class);
                assertThat(context).hasSingleBean(FileController.class);
                assertThat(context.getBeansOfType(DownloadController.class)).hasSize(1);
                assertThat(context).hasSingleBean(MeterRegistry.class);
            });
    }

    @Test
    void localFallbackIsPresentButMinioDoesNotInstallIt() {
        new ApplicationContextRunner()
            .withUserConfiguration(LocalFallbackConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withPropertyValues("file.storage.type=local")
            .run(context -> assertThat(context).hasSingleBean(LocalTemporaryFallbackCleaner.class));

        new ApplicationContextRunner()
            .withUserConfiguration(LocalFallbackConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withPropertyValues("file.storage.type=minio")
            .run(context -> assertThat(context).doesNotHaveBean(LocalTemporaryFallbackCleaner.class));
    }

    @Test
    void maintenanceSchedulerKeepsLocalFallbackWiring() {
        DataSource dataSource = mock(DataSource.class);
        new ApplicationContextRunner()
            .withUserConfiguration(FileServiceWiringConfiguration.class, CleanupMaintenanceConfiguration.class,
                TrustedHeaderIdentityConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withPropertyValues("file.maintenance.enabled=true", "file.storage.type=local",
                "file.identity.trusted-header-enabled=true", "spring.profiles.active=test", "file.cleanup.schedule=1m")
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(CleanupScheduler.class);
                assertThat(context).hasSingleBean(LocalTemporaryFallbackCleaner.class);
                assertThat(context).hasSingleBean(com.example.files.api.CleanupMaintenanceController.class);
            });
    }

    @Test
    void minioProfileKeepsSchedulerWithoutLocalFallback() {
        new ApplicationContextRunner()
            .withUserConfiguration(CleanupMaintenanceConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withBean(JdbcCleanupTaskRepository.class, () -> mock(JdbcCleanupTaskRepository.class))
            .withBean(ObjectStorage.class, () -> mock(ObjectStorage.class))
            .withBean(UploadSessionRepository.class, () -> mock(UploadSessionRepository.class))
            .withBean(BlobRepository.class, () -> mock(BlobRepository.class))
            .withBean(StagingRecoveryService.class, () -> mock(StagingRecoveryService.class))
            .withPropertyValues("file.maintenance.enabled=true", "file.storage.type=minio",
                "file.cleanup.schedule=1m")
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(CleanupScheduler.class);
                assertThat(context).doesNotHaveBean(LocalTemporaryFallbackCleaner.class);
            });
    }

    @Test
    void maintenanceRouteRequiresEnabledTestProfileAndIdentityResolver() {
        DataSource dataSource = mock(DataSource.class);
        new ApplicationContextRunner()
            .withUserConfiguration(FileServiceWiringConfiguration.class, CleanupMaintenanceConfiguration.class,
                TrustedHeaderIdentityConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withPropertyValues("file.maintenance.enabled=false", "file.identity.trusted-header-enabled=true",
                "spring.profiles.active=test", "file.cleanup.schedule=1m")
            .run(context -> {
                assertThat(context).doesNotHaveBean(CleanupScheduler.class);
                assertThat(context).doesNotHaveBean(com.example.files.api.CleanupMaintenanceController.class);
            });

        new ApplicationContextRunner()
            .withUserConfiguration(FileServiceWiringConfiguration.class, CleanupMaintenanceConfiguration.class,
                TrustedHeaderIdentityConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withPropertyValues("file.maintenance.enabled=true", "file.identity.trusted-header-enabled=true",
                "spring.profiles.active=prod", "file.cleanup.schedule=1m")
            .run(context -> {
                assertThat(context).hasSingleBean(CleanupScheduler.class);
                assertThat(context).doesNotHaveBean(com.example.files.api.CleanupMaintenanceController.class);
            });

        new ApplicationContextRunner()
            .withUserConfiguration(FileServiceWiringConfiguration.class, CleanupMaintenanceConfiguration.class,
                TrustedHeaderIdentityConfiguration.class)
            .withBean(FileServiceProperties.class, FileServiceWiringConfigurationTest::properties)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withPropertyValues("file.maintenance.enabled=true", "file.identity.trusted-header-enabled=false",
                "spring.profiles.active=test", "file.cleanup.schedule=1m")
            .run(context -> {
                assertThat(context).hasSingleBean(CleanupScheduler.class);
                assertThat(context).doesNotHaveBean(com.example.files.api.CleanupMaintenanceController.class);
            });
    }

    private static FileServiceProperties properties() {
        return new FileServiceProperties(FileServiceProperties.SECURITY_MAX_SIZE,
            java.time.Duration.ofHours(1), java.time.Duration.ofMinutes(2),
            java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(100),
            new FileServiceProperties.Cleanup(50, java.time.Duration.ofSeconds(30),
                java.util.List.of(java.time.Duration.ofSeconds(5)), 5, java.time.Duration.ofHours(24)),
            new FileServiceProperties.Download(java.time.Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(true), new FileServiceProperties.Storage("local",
                "target/wiring-test-storage", "http://localhost:9000", "", "", "secure-files"),
            new FileServiceProperties.Maintenance(false));
    }
}
