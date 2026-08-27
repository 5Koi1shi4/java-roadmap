package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL、Spring RANDOM_PORT 与对象流包装器验证下载分块和 wiring。 */
@SpringBootTest(classes = {FileServiceApplication.class, DownloadStreamingHttpIT.Beans.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true",
        "file.download.local-hmac-secret=01234567890123456789012345678901",
        "file.storage.local-root=target/download-streaming-http-storage"})
@ActiveProfiles("test")
class DownloadStreamingHttpIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InstrumentedStorage storage;
    @Autowired private org.springframework.context.ApplicationContext applicationContext;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void streamsTwentyKilobytePdfWithMultipleReadsAndOneClose() throws Exception {
        byte[] expected = paddedPdf(20 * 1024);
        String fileId = upload(840L, expected);
        storage.reset();

        ResponseEntity<byte[]> response = client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(840L)), byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(expected);
        assertThat(storage.reads()).isGreaterThanOrEqualTo(3);
        assertThat(storage.closes()).isEqualTo(1);
    }

    @Test
    void productionWiringHasOneDownloadControllerAndMeterRegistry() {
        assertThat(applicationContext.getBeansOfType(com.example.files.api.DownloadController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MeterRegistry.class)).hasSize(1);
        assertThat(meterRegistry).isNotNull();
        assertThat(client.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(client.getForEntity("/actuator/metrics", String.class).getStatusCode().value()).isNotEqualTo(200);
    }

    private String upload(long userId, byte[] bytes) throws Exception {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "large.pdf");
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "large.pdf"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, part));
        ResponseEntity<String> response = client.postForEntity("/api/files",
            new HttpEntity<>(body, headers(userId)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode node = objectMapper.readTree(response.getBody());
        return node.path("fileId").asText();
    }

    private static byte[] paddedPdf(int size) {
        byte[] bytes = new byte[size];
        byte[] prefix = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        java.util.Arrays.fill(bytes, prefix.length, bytes.length, (byte) 'x');
        return bytes;
    }

    private static HttpHeaders headers(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", Long.toString(userId));
        return headers;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        @Primary
        InstrumentedStorage instrumentedStorage(LocalObjectStorage delegate) {
            return new InstrumentedStorage(delegate);
        }
    }

    static final class InstrumentedStorage implements ObjectStorage {
        private final LocalObjectStorage delegate;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        InstrumentedStorage(LocalObjectStorage delegate) { this.delegate = delegate; }

        void reset() { reads.set(0); closes.set(0); }
        int reads() { return reads.get(); }
        int closes() { return closes.get(); }

        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            return delegate.writeTemporary(key, source, maxBytes);
        }
        @Override public void commit(String tempKey, String objectKey) { delegate.commit(tempKey, objectKey); }
        @Override public InputStream open(String objectKey) {
            return new FilterInputStream(delegate.open(objectKey)) {
                @Override public int read() throws IOException { reads.incrementAndGet(); return super.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    reads.incrementAndGet(); return super.read(b, off, len);
                }
                @Override public void close() throws IOException {
                    closes.incrementAndGet(); super.close();
                }
            };
        }
        @Override public StorageObjectMetadata stat(String key) { return delegate.stat(key); }
        @Override public void delete(String key) { delegate.delete(key); }
        @Override public Optional<URI> createPresignedGet(String key, Duration ttl, Map<String, String> headers) {
            return delegate.createPresignedGet(key, ttl, headers);
        }
    }
}
