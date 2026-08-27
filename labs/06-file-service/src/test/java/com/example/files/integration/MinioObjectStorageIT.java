package com.example.files.integration;

import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.infrastructure.storage.StorageConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 MinIO 对象语义、条件提交、中文响应头与临时生命周期验证。 */
class MinioObjectStorageIT extends SharedStorageContainers {
    private final MinioObjectStorage storage = new MinioObjectStorage(minioClient(), minioStorage());

    MinioObjectStorageIT() { storage.initialize(); }

    @AfterEach
    void restoreDeletePolicy() { allowTemporaryDeletes(); }

    @Test
    void writesCommitsReadsStatsDeletesAndPresignsThroughProxy() throws Exception {
        byte[] bytes = "真实 MinIO 内容".getBytes(StandardCharsets.UTF_8);
        String temp = key("tmp");
        String object = key("blobs");
        TemporaryObject written = storage.writeTemporary(temp, new ByteArrayInputStream(bytes), 1024);
        assertThat(written.size()).isEqualTo(bytes.length);
        storage.commit(temp, object);
        assertThat(storage.exists(temp)).isFalse();
        assertThat(storage.stat(object).size()).isEqualTo(bytes.length);
        assertThat(storage.open(object).readAllBytes()).containsExactly(bytes);
        URI link = storage.createPresignedGet(object, Duration.ofSeconds(120), Map.of(
            "response-content-type", "application/octet-stream",
            "response-content-disposition", "attachment; filename=\"资料.pdf\"; filename*=UTF-8''%E8%B5%84%E6%96%99.pdf")).orElseThrow();
        assertThat(link.toString()).contains("X-Amz-Expires=120");
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(link).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsExactly(bytes);
        URI shortLink = storage.createPresignedGet(object, Duration.ofSeconds(1), Map.of()).orElseThrow();
        Thread.sleep(2200);
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(shortLink).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isNotEqualTo(200);
        storage.delete(object);
        assertThatThrownBy(() -> storage.stat(object)).isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void concurrentCommitsToSameDestinationHaveOneWinnerWithoutOverwritingWinnerBytes() throws Exception {
        String tempA = key("tmp");
        String tempB = key("tmp");
        String destination = key("blobs");
        byte[] a = "winner-A".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "winner-B".getBytes(StandardCharsets.US_ASCII);
        storage.writeTemporary(tempA, new ByteArrayInputStream(a), 1024);
        storage.writeTemporary(tempB, new ByteArrayInputStream(b), 1024);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit((Callable<Boolean>) () -> commitWithoutFailure(tempA, destination));
            var second = executor.submit((Callable<Boolean>) () -> commitWithoutFailure(tempB, destination));
            assertThat(first.get()).isNotEqualTo(second.get());
            byte[] expected = first.get() ? a : b;
            assertThat(storage.open(destination).readAllBytes()).containsExactly(expected);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void formalObjectSurvivesTemporaryDeleteFailureAndTemporaryCanBeCompensated() throws Exception {
        String temp = key("tmp");
        String object = key("blobs");
        byte[] bytes = "formal-object".getBytes(StandardCharsets.US_ASCII);
        storage.writeTemporary(temp, new ByteArrayInputStream(bytes), 1024);
        denyTemporaryDeletes();
        storage.commit(temp, object);
        assertThat(storage.open(object).readAllBytes()).containsExactly(bytes);
        assertThat(storage.exists(temp)).isTrue();
        allowTemporaryDeletes();
        storage.delete(temp);
        assertThat(storage.exists(temp)).isFalse();
    }

    @Test
    void rejectsKeysOutsideOpaqueTmpAndBlobNamespaces() {
        assertThatThrownBy(() -> storage.exists("tmp/../blobs/" + UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.createPresignedGet("private/" + UUID.randomUUID(), Duration.ofSeconds(1), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycleOnlyMatchesTemporaryPrefixForTwentyFourHours() {
        var lifecycle = storage.lifecycle();
        assertThat(lifecycle.rules()).hasSize(1);
        var rule = lifecycle.rules().get(0);
        assertThat(rule.filter().prefix()).isEqualTo("tmp/");
        assertThat(rule.expiration().days()).isEqualTo(1);
    }

    private boolean commitWithoutFailure(String temp, String destination) {
        try {
            storage.commit(temp, destination);
            return true;
        } catch (StorageConflictException expected) {
            return false;
        }
    }

    private static String key(String namespace) { return namespace + "/" + UUID.randomUUID(); }
}
