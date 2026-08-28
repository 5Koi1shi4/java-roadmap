package com.example.files.unit;

import com.example.files.application.cleanup.CleanupSummary;
import com.example.files.application.cleanup.ExpiredUploadService;
import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.observability.CleanupScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** 调度两个阶段必须隔离故障，并保留可观测的失败路径。 */
class CleanupSchedulerTest {
    @Test
    void expirationFailureDoesNotPreventTaskBatch() {
        StorageCleanupService cleanup = mock(StorageCleanupService.class);
        ExpiredUploadService expiration = mock(ExpiredUploadService.class);
        LocalTemporaryFallbackCleaner fallback = mock(LocalTemporaryFallbackCleaner.class);
        when(expiration.expireUploads(anyString())).thenThrow(new DataAccessResourceFailureException("db unavailable"));
        when(cleanup.runBatch(anyString())).thenReturn(new CleanupSummary(0, 0, 0, 0));
        assertThatThrownBy(() -> new CleanupScheduler(cleanup, expiration, fallback).run())
            .isInstanceOf(DataAccessResourceFailureException.class).hasMessage("db unavailable");
        verify(cleanup).runBatch(anyString());
        verify(fallback).clean();
    }

    @Test
    void taskBatchFailureRunsFallbackAndExposesTheFailure() {
        StorageCleanupService cleanup = mock(StorageCleanupService.class);
        ExpiredUploadService expiration = mock(ExpiredUploadService.class);
        LocalTemporaryFallbackCleaner fallback = mock(LocalTemporaryFallbackCleaner.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("task db unavailable");
        when(cleanup.runBatch(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> new CleanupScheduler(cleanup, expiration, fallback).run())
            .isSameAs(failure);
        verify(expiration).expireUploads(anyString());
        verify(cleanup).runBatch(anyString());
        verify(fallback).clean();
    }

    @Test
    void bothDatabaseStagesRunAndFallbackOnlyOnce() {
        StorageCleanupService cleanup = mock(StorageCleanupService.class);
        ExpiredUploadService expiration = mock(ExpiredUploadService.class);
        LocalTemporaryFallbackCleaner fallback = mock(LocalTemporaryFallbackCleaner.class);
        DataAccessResourceFailureException expirationFailure = new DataAccessResourceFailureException("expire db unavailable");
        DataAccessResourceFailureException batchFailure = new DataAccessResourceFailureException("batch db unavailable");
        when(expiration.expireUploads(anyString())).thenThrow(expirationFailure);
        when(cleanup.runBatch(anyString())).thenThrow(batchFailure);

        assertThatThrownBy(() -> new CleanupScheduler(cleanup, expiration, fallback).run())
            .isSameAs(expirationFailure)
            .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(batchFailure));
        verify(expiration).expireUploads(anyString());
        verify(cleanup).runBatch(anyString());
        verify(fallback, times(1)).clean();
    }

    @Test
    void fallbackFailureIsVisibleWithoutSkippingRequiredStage() {
        StorageCleanupService cleanup = mock(StorageCleanupService.class);
        ExpiredUploadService expiration = mock(ExpiredUploadService.class);
        LocalTemporaryFallbackCleaner fallback = mock(LocalTemporaryFallbackCleaner.class);
        DataAccessResourceFailureException expirationFailure = new DataAccessResourceFailureException("expire db unavailable");
        RuntimeException fallbackFailure = new IllegalStateException("fallback unavailable");
        when(expiration.expireUploads(anyString())).thenThrow(expirationFailure);
        when(cleanup.runBatch(anyString())).thenReturn(new CleanupSummary(0, 0, 0, 0));
        doThrow(fallbackFailure).when(fallback).clean();

        assertThatThrownBy(() -> new CleanupScheduler(cleanup, expiration, fallback).run())
            .isSameAs(expirationFailure)
            .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(fallbackFailure));
        verify(cleanup).runBatch(anyString());
        verify(fallback).clean();
    }
}
