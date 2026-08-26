package com.example.files.unit;

import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.infrastructure.storage.SecureStorageKeyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureStorageKeyFactoryTest {
    @TempDir
    Path root;

    @Test
    void generatesOnlyOpaqueTemporaryAndBlobUuidKeys() {
        assertThat(SecureStorageKeyFactory.temporaryKey())
            .matches("tmp/[0-9a-f-]{36}");
        assertThat(SecureStorageKeyFactory.objectKey())
            .matches("blobs/[0-9a-f-]{36}");
    }

    @Test
    void rejectsTraversalAbsoluteAndUnknownNamespaceKeys() {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        for (String key : new String[] {"../secret", "/absolute", "other/11111111-1111-1111-1111-111111111111"}) {
            assertThatThrownBy(() -> storage.resolveInsideRoot(key))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
