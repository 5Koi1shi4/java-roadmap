package com.example.files.integration;

import com.example.files.application.upload.TemporaryObject;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    @Test
    void writeTemporaryEnforcesItsOwnByteLimit() {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        String key = "tmp/33333333-3333-3333-3333-333333333333";

        assertThatThrownBy(() -> storage.writeTemporary(key,
            new ByteArrayInputStream(new byte[21]), 20))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("FILE_TOO_LARGE");
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void commitRejectsDirectoriesAndNeverOverwritesAnExistingTarget() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        String directoryKey = "tmp/44444444-4444-4444-4444-444444444444";
        Files.createDirectories(root.resolve(directoryKey));
        assertThatThrownBy(() -> storage.commit(directoryKey,
            "blobs/55555555-5555-5555-5555-555555555555"))
            .isInstanceOf(IllegalArgumentException.class);

        String tempKey = "tmp/66666666-6666-6666-6666-666666666666";
        String targetKey = "blobs/77777777-7777-7777-7777-777777777777";
        storage.writeTemporary(tempKey, new ByteArrayInputStream(new byte[] {1, 2}), 20);
        Path target = root.resolve(targetKey);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {9}, java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThatThrownBy(() -> storage.commit(tempKey, targetKey))
            .isInstanceOf(RuntimeException.class);
        assertThat(Files.readAllBytes(target)).containsExactly((byte) 9);
    }

    @Test
    void refusesExistingSymlinkPathsWhenPlatformSupportsSymlinks() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        Path outside = root.resolveSibling(root.getFileName() + "-outside");
        Files.createDirectories(outside);
        Path link = root.resolve("blobs");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            Assumptions.assumeTrue(false, "symbolic links unavailable on this platform");
            return;
        }
        assertThatThrownBy(() -> storage.open("blobs/88888888-8888-8888-8888-888888888888"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesSymlinkSourceAndTargetWhenPlatformSupportsSymlinks() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        Path outside = root.resolveSibling(root.getFileName() + "-objects");
        Files.createDirectories(outside);
        Path sourceLink = root.resolve("tmp/99999999-9999-9999-9999-999999999999");
        Path targetLink = root.resolve("blobs/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Files.createDirectories(sourceLink.getParent());
        Files.createDirectories(targetLink.getParent());
        Path outsideFile = outside.resolve("source");
        Files.write(outsideFile, new byte[] {1});
        try {
            Files.createSymbolicLink(sourceLink, outsideFile);
            Files.createSymbolicLink(targetLink, outsideFile);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            Assumptions.assumeTrue(false, "symbolic links unavailable on this platform");
            return;
        }
        assertThatThrownBy(() -> storage.commit(sourceLink.getParent().getFileName() + "/"
            + sourceLink.getFileName(), "blobs/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            .isInstanceOf(IllegalArgumentException.class);
        storage.writeTemporary("tmp/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            new ByteArrayInputStream(new byte[] {2}), 20);
        assertThatThrownBy(() -> storage.commit("tmp/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "blobs/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .isInstanceOf(RuntimeException.class);
    }
}
