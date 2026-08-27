package com.example.files.integration;

import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
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
}
