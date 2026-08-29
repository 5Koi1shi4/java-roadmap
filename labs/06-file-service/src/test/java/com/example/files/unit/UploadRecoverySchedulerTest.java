package com.example.files.unit;

import com.example.files.application.cleanup.CleanupSummary;
import com.example.files.application.cleanup.ExpiredUploadService;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.config.FileServiceProperties;
import com.example.files.observability.UploadRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UploadRecoverySchedulerTest {
    @Test
    void runsCleanupEvenWhenRecoveryStageFails() {
        ExpiredUploadService recovery = mock(ExpiredUploadService.class);
        StorageCleanupService cleanup = mock(StorageCleanupService.class);
        RuntimeException failure = new IllegalStateException("recovery unavailable");
        doThrow(failure).when(recovery).expireUploads(anyString());
        when(cleanup.runBatch(anyString())).thenReturn(new CleanupSummary(0, 0, 0, 0));
        UploadRecoveryScheduler scheduler = new UploadRecoveryScheduler(recovery, cleanup, properties(), null);

        assertThatThrownBy(scheduler::run).isSameAs(failure);
        verify(recovery).expireUploads(anyString());
        verify(cleanup).runBatch(anyString());
    }

    @Test
    void usesTypeSafeConfiguredScheduleOnScheduledEntryPoint() throws Exception {
        Scheduled scheduled = UploadRecoveryScheduler.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${file.cleanup.schedule}");
        assertThat(properties().cleanup().schedule()).isEqualTo(Duration.ofSeconds(7));
    }

    private static FileServiceProperties properties() {
        return new FileServiceProperties(FileServiceProperties.SECURITY_MAX_SIZE, Duration.ofHours(1),
            Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofMillis(100),
            new FileServiceProperties.Cleanup(10, Duration.ofSeconds(30), java.util.List.of(Duration.ofSeconds(1)), 3,
                Duration.ofHours(1), Duration.ofSeconds(7)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(true),
            new FileServiceProperties.Storage("local", "target/scheduler-test-storage", "http://localhost:9000", "", "", "secure-files"),
            new FileServiceProperties.Maintenance(false));
    }
}
