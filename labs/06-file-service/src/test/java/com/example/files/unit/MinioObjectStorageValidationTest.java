package com.example.files.unit;

import com.example.files.infrastructure.storage.MinioObjectStorage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioObjectStorageValidationTest {
    @Test
    void rejectsTraversalAndCrossNamespaceKeysBeforeSdkCalls() {
        assertThatThrownBy(() -> MinioObjectStorage.validateKey("../tmp/00000000-0000-0000-0000-000000000000", "tmp"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinioObjectStorage.validateKey("blobs/00000000-0000-0000-0000-000000000000", "tmp"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinioObjectStorage.validateKey("tmp/not-a-uuid", "tmp"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
