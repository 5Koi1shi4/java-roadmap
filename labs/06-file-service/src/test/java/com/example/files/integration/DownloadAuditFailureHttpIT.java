package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.AuditAction;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.infrastructure.persistence.JdbcAuditRecorder;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 授权审计失败时，真实 HTTP 必须 fail-close，且不能打开对象流。 */
@SpringBootTest(classes = {FileServiceApplication.class, DownloadAuditFailureHttpIT.Beans.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true",
        "file.download.local-hmac-secret=01234567890123456789012345678901",
        "file.storage.local-root=target/download-audit-failure-http-storage"})
@ActiveProfiles("test")
class DownloadAuditFailureHttpIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FailingAuditRecorder audit;
    @Autowired private OpenCountingStorage storage;

    @Test
    void authorizedAuditFailureReturns503EmptyBodyWithoutOpeningStream() throws Exception {
        String fileId = upload(860L);
        storage.reset();
        audit.failNextAuthorized();

        ResponseEntity<String> response = client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(860L)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNullOrEmpty();
        assertThat(storage.opens()).isZero();
    }

    private String upload(long userId) throws Exception {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "audit.pdf");
        ByteArrayResource resource = new ByteArrayResource("%PDF-1.7 audit".getBytes(StandardCharsets.US_ASCII)) {
            @Override public String getFilename() { return "audit.pdf"; }
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
        FailingAuditRecorder failingAuditRecorder(JdbcAuditRecorder delegate) {
            return new FailingAuditRecorder(delegate);
        }

        @Bean @Primary
        OpenCountingStorage auditFailureStorage(LocalObjectStorage delegate) {
            return new OpenCountingStorage(delegate);
        }
    }

    static final class FailingAuditRecorder implements AuditRecorder {
        private final JdbcAuditRecorder delegate;
        private final AtomicBoolean failAuthorized = new AtomicBoolean();

        FailingAuditRecorder(JdbcAuditRecorder delegate) { this.delegate = delegate; }
        void failNextAuthorized() { failAuthorized.set(true); }

        @Override public void record(AuditEvent event) {
            if (event.action() == AuditAction.DOWNLOAD_AUTHORIZED && failAuthorized.compareAndSet(true, false)) {
                throw new DataAccessResourceFailureException("audit backend secret");
            }
            delegate.record(event);
        }
    }

    static class OpenCountingStorage implements ObjectStorage {
        private final LocalObjectStorage delegate;
        private final AtomicInteger opens = new AtomicInteger();
        OpenCountingStorage(LocalObjectStorage delegate) { this.delegate = delegate; }
        void reset() { opens.set(0); }
        int opens() { return opens.get(); }
        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            return delegate.writeTemporary(key, source, maxBytes);
        }
        @Override public void commit(String tempKey, String objectKey) { delegate.commit(tempKey, objectKey); }
        @Override public InputStream open(String key) { opens.incrementAndGet(); return delegate.open(key); }
        @Override public StorageObjectMetadata stat(String key) { return delegate.stat(key); }
        @Override public void delete(String key) { delegate.delete(key); }
        @Override public Optional<URI> createPresignedGet(String key, Duration ttl, Map<String, String> headers) {
            return delegate.createPresignedGet(key, ttl, headers);
        }
    }
}
