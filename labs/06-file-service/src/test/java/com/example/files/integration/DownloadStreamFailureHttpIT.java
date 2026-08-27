package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.infrastructure.storage.LocalObjectStorage;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 HTTP 流中途 IOException + close RuntimeException 的终态与观测验收。 */
@SpringBootTest(classes = {FileServiceApplication.class, DownloadStreamFailureHttpIT.Beans.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true",
        "file.download.local-hmac-secret=01234567890123456789012345678901",
        "file.storage.local-root=target/download-stream-failure-http-storage"})
@ActiveProfiles("test")
class DownloadStreamFailureHttpIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FailingStorage storage;

    @Test
    void midstreamIoAndCloseRuntimeProduceOneFailedAuditAndFixedCounter() throws Exception {
        String fileId = upload(870L);
        UUID id = UUID.fromString(fileId);
        double before = Optional.ofNullable(meterRegistry.find("file.download.failures")
            .tag("reason", "STREAM_FAILED").counter()).map(c -> c.count()).orElse(0D);

        try {
            client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
                new HttpEntity<>(headers(870L)), byte[].class);
        } catch (RestClientException ignored) {
            // Servlet 响应可能已提交 200 后因下游流失败而由 HTTP 客户端报告连接异常。
        }

        assertThat(storage.closeCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event "
            + "WHERE file_id=? AND action='DOWNLOAD_FAILED'", Integer.class, fileId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event "
            + "WHERE file_id=? AND action='DOWNLOAD_COMPLETED'", Integer.class, fileId)).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM file_audit_event "
            + "WHERE file_id=? AND action='DOWNLOAD_FAILED' ORDER BY id DESC LIMIT 1", String.class, fileId))
            .isEqualTo("STREAM_FAILED");
        assertThat(meterRegistry.get("file.download.failures").tag("reason", "STREAM_FAILED").counter().count())
            .isEqualTo(before + 1D);
    }

    private String upload(long userId) throws Exception {
        byte[] bytes = new byte[20 * 1024];
        byte[] prefix = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        java.util.Arrays.fill(bytes, prefix.length, bytes.length, (byte) 'f');
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "failure.pdf");
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "failure.pdf"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, part));
        ResponseEntity<String> response = client.postForEntity("/api/files",
            new HttpEntity<>(body, headers(userId)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return objectMapper.readTree(response.getBody()).path("fileId").asText();
    }

    private static HttpHeaders headers(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", Long.toString(userId));
        return headers;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean @Primary
        FailingStorage streamFailureStorage(LocalObjectStorage delegate) { return new FailingStorage(delegate); }
    }

    static final class FailingStorage implements ObjectStorage {
        private final LocalObjectStorage delegate;
        private final AtomicInteger closeCalls = new AtomicInteger();
        FailingStorage(LocalObjectStorage delegate) { this.delegate = delegate; }
        int closeCalls() { return closeCalls.get(); }
        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            return delegate.writeTemporary(key, source, maxBytes);
        }
        @Override public void commit(String tempKey, String objectKey) { delegate.commit(tempKey, objectKey); }
        @Override public InputStream open(String key) {
            return new FilterInputStream(delegate.open(key)) {
                private boolean prefetched;
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (!prefetched) {
                        prefetched = true;
                        return super.read(b, off, len);
                    }
                    throw new IOException("stream backend failed");
                }
                @Override public int read() throws IOException { throw new IOException("stream backend failed"); }
                @Override public void close() {
                    closeCalls.incrementAndGet();
                    throw new IllegalStateException("close backend failed");
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
