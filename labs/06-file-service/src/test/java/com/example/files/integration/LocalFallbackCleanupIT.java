package com.example.files.integration;

import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import org.junit.jupiter.api.Test;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.cleanup.ExpiredUploadService;
import com.example.files.observability.CleanupScheduler;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.assertThat;

/** 本地 fallback 只处理 tmp，正式 blobs 永不扫描。 */
class LocalFallbackCleanupIT {
    @Test void deletesOnlyOldTemporaryObject() throws Exception {
        Path root = Files.createTempDirectory("cleanup-fallback");
        Path tmp = Files.createDirectories(root.resolve("tmp"));
        Path blobs = Files.createDirectories(root.resolve("blobs"));
        Path old = Files.writeString(tmp.resolve("00000000-0000-0000-0000-000000000001"), "old");
        Path fresh = Files.writeString(tmp.resolve("00000000-0000-0000-0000-000000000002"), "fresh");
        Path formal = Files.writeString(blobs.resolve("00000000-0000-0000-0000-000000000003"), "blob");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(25))));
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.now().minus(Duration.ofMinutes(1))));
        assertThat(new LocalTemporaryFallbackCleaner(root, Duration.ofHours(24)).clean()).isOne();
        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(formal)).isTrue();
    }
    @Test void schedulerFallbackContractRunsWhenThePrimaryDatabaseCleanupFails() {
        StorageCleanupService primary = mock(StorageCleanupService.class);
        ExpiredUploadService expired = mock(ExpiredUploadService.class);
        LocalTemporaryFallbackCleaner fallback = mock(LocalTemporaryFallbackCleaner.class);
        when(expired.expireUploads(anyString())).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        when(primary.runBatch(anyString())).thenReturn(new com.example.files.application.cleanup.CleanupSummary(0, 0, 0, 0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CleanupScheduler(primary, expired, fallback).run())
            .isInstanceOf(DataAccessResourceFailureException.class);
        verify(fallback).clean();
        verify(primary).runBatch(anyString());
    }

    @Test void rejectsSymbolicLinkTemporaryDirectory() throws Exception {
        Path root = Files.createTempDirectory("cleanup-fallback-link");
        Path outside = Files.createTempDirectory("cleanup-fallback-outside");
        Path target = outside.resolve("target");
        Files.writeString(target, "outside");
        Path tmp = root.resolve("tmp");
        try {
            Files.createSymbolicLink(tmp, outside);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException unsupported) {
            // 当前运行器可能禁用创建链接；仍验证未创建链接时兜底不会越界扫描。
            assertThat(new LocalTemporaryFallbackCleaner(root, Duration.ofHours(24)).clean()).isZero();
            assertThat(Files.exists(target)).isTrue();
            return;
        }
        assertThat(new LocalTemporaryFallbackCleaner(root, Duration.ofHours(24)).clean()).isZero();
        assertThat(Files.exists(target)).isTrue();
    }
}
