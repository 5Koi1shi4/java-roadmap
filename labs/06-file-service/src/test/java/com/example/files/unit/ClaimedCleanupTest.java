package com.example.files.unit;

import com.example.files.application.cleanup.ClaimedCleanup;
import com.example.files.application.cleanup.CleanupTaskType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimedCleanupTest {
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID TOKEN = UUID.randomUUID();

    @Test
    void rejectsNonPositiveTargetGeneration() {
        assertThatThrownBy(() -> new ClaimedCleanup(TASK_ID, CleanupTaskType.BLOB_OBJECT, "7", 0,
            "blobs/" + UUID.randomUUID(), TOKEN, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankTargetId() {
        assertThatThrownBy(() -> new ClaimedCleanup(TASK_ID, CleanupTaskType.TEMP_OBJECT, " ", 1,
            "tmp/" + UUID.randomUUID(), TOKEN, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeObjectKey() {
        assertThatThrownBy(() -> new ClaimedCleanup(TASK_ID, CleanupTaskType.BLOB_OBJECT, "7", 1,
            "../blobs/" + UUID.randomUUID(), TOKEN, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsStorageKeyWithSafeAdditionalSegments() {
        new ClaimedCleanup(TASK_ID, CleanupTaskType.BLOB_OBJECT, "7", 1,
            "blobs/archive/" + UUID.randomUUID(), TOKEN, 0);
    }
}
