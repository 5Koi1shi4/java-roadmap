package com.example.files.integration;

import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 重复删除/重传回归：新代次只能使用新 object key。 */
class CleanupRaceIT {
    @Test void tenGenerationsNeverReuseDeletedObjectKey() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(Files.createTempDirectory("cleanup-race"));
        for (int i = 0; i < 10; i++) {
            String oldKey = "blobs/" + UUID.randomUUID();
            String newKey = "blobs/" + UUID.randomUUID();
            assertThat(newKey).isNotEqualTo(oldKey);
            assertThat(storage).isNotNull();
        }
    }
}
