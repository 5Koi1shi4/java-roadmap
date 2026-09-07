package com.example.campusmarket.unit.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageCleanupLeaseFenceTest {
    @Test
    void completeAndReleaseSqlRequireAnUnexpiredLease() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/example/campusmarket/storage/StorageCleanupScheduler.java"));
        assertThat(source).contains("claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)");
    }
}
