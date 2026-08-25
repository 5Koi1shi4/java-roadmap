package com.example.search.infrastructure.elasticsearch;

import com.example.search.config.SearchProperties;
import org.elasticsearch.client.Request;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchClientConfigurationTest {
    @Test
    void appliesConfiguredRequestTimeoutToTheRealRestClient() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().readNBytes(1);
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (Exception ignored) { }
            });
            SearchProperties properties = new SearchProperties(7, Duration.ofSeconds(12),
                    Duration.ofMillis(200), Duration.ofSeconds(1), new SearchProperties.Maintenance(false));
            long started = System.nanoTime();
            try (var client = new ElasticsearchClientConfiguration().elasticsearchRestClient(
                    "http://localhost:" + server.getLocalPort(), properties)) {
                assertThatThrownBy(() -> client.performRequest(new Request("GET", "/")))
                        .isInstanceOf(java.io.IOException.class);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMillis).isLessThan(5_000);
            accepted.cancel(true);
        }
    }
}
