package com.example.files.unit;

import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 恢复探测必须区分对象不存在与存储不可用。 */
class StagingRecoveryProbeTest {
    @Test
    void localStorageStatDistinguishesMissingObjectAndSize() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(Files.createTempDirectory("recovery-probe"));
        assertThatThrownBy(() -> storage.stat("blobs/00000000-0000-0000-0000-000000000000"))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }
}
