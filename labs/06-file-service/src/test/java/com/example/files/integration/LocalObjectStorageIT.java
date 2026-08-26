package com.example.files.integration;

import com.example.files.application.upload.TemporaryObject;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalObjectStorageIT {

    @TempDir
    Path root;

    @Test
    void writesCommitsReadsAndDeletesObjectsWithinRoot() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        TemporaryObject temp = storage.writeTemporary("tmp/11111111-1111-1111-1111-111111111111",
            new ByteArrayInputStream(bytes), 20);
        storage.commit(temp.key(), "blobs/22222222-2222-2222-2222-222222222222");

        assertThat(storage.open("blobs/22222222-2222-2222-2222-222222222222").readAllBytes())
            .containsExactly(bytes);
        assertThat(storage.createPresignedGet("blobs/22222222-2222-2222-2222-222222222222",
            Duration.ofMinutes(1), Map.of())).isEmpty();
        storage.delete("blobs/22222222-2222-2222-2222-222222222222");
        assertThatThrownBy(() -> storage.open("blobs/22222222-2222-2222-2222-222222222222"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void localStorageNeverResolvesOutsideConfiguredRoot() {
        LocalObjectStorage storage = new LocalObjectStorage(root);

        assertThatThrownBy(() -> storage.open("../secret"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
