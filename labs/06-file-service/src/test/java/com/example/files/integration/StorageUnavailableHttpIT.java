package com.example.files.integration;

import com.example.files.api.ApiExceptionHandler;
import com.example.files.api.CorrelationIdFilter;
import com.example.files.api.FileController;
import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageCoordinationUnavailableException;
import com.example.files.application.upload.UploadFailureClassifier;
import com.example.files.application.upload.UploadInspector;
import com.example.files.application.upload.UploadService;
import com.example.files.application.upload.StagingWaitPolicy;
import com.example.files.config.TrustedHeaderIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立真实端口上下文，验证协调不可用异常穿过 Spring MVC 后稳定映射为 503。 */
@SpringBootTest(classes = StorageUnavailableHttpIT.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "file.identity.trusted-header-enabled=true")
@ActiveProfiles("test")
class StorageUnavailableHttpIT {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate client;

    @Test
    void mapsStorageCoordinationFailureToUtf8503WithoutLeakingCause() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", "42");
        ResponseEntity<String> response = client.exchange("/api/files", HttpMethod.POST,
            new HttpEntity<>(multipartBody(), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).hasSize(22)
            .matches("[A-Za-z0-9_-]{22}");
        assertThat(response.getBody()).contains("\"code\":\"SERVICE_UNAVAILABLE\"")
            .contains("\"message\":\"文件服务暂不可用\"")
            .doesNotContain("backend-secret", "storage coordination timed out", "StorageCoordinationUnavailableException");
        assertThat(response.getBody()).containsPattern("\"correlationId\":\"[A-Za-z0-9_-]{22}\"");
    }

    private MultiValueMap<String, Object> multipartBody() {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        partHeaders.setContentDispositionFormData("file", "课程资料.pdf");
        ByteArrayResource resource = new ByteArrayResource("%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII)) {
            @Override public String getFilename() { return "课程资料.pdf"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        return body;
    }

    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({TrustedHeaderIdentityConfiguration.class, FileController.class,
        ApiExceptionHandler.class, CorrelationIdFilter.class, TestBeans.class})
    static class TestApplication { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        UploadService unavailableUploadService() {
            UploadService.Transactions transactions = new UploadService.Transactions() {
                @Override
                public com.example.files.domain.UploadSession begin(long actorId,
                        com.example.files.domain.SafeDisplayName name, String declaredType) {
                    throw new StorageCoordinationUnavailableException("backend-secret");
                }

                @Override public com.example.files.application.upload.BlobReservation reserve(
                    java.util.UUID sessionId, java.util.UUID ownerToken,
                    com.example.files.application.upload.InspectedUpload upload) { return null; }
                @Override public com.example.files.application.upload.BlobReservation resolve(
                    java.util.UUID sessionId, java.util.UUID ownerToken,
                    com.example.files.application.upload.InspectedUpload upload) { return null; }
                @Override public com.example.files.application.upload.UploadResult finalizeUpload(
                    com.example.files.application.upload.BlobReservation.Granted reservation) { return null; }
                @Override public com.example.files.application.upload.UploadResult finalizeUpload(
                    com.example.files.application.upload.BlobReservation.Granted reservation,
                    com.example.files.application.audit.CorrelationId correlationId) { return null; }
                @Override public com.example.files.application.upload.UploadResult attachReadyBlob(
                    com.example.files.application.upload.BlobReservation.ReadyReuse reservation) { return null; }
                @Override public com.example.files.application.upload.UploadResult attachReadyBlob(
                    com.example.files.application.upload.BlobReservation.ReadyReuse reservation,
                    com.example.files.application.audit.CorrelationId correlationId) { return null; }
                @Override public void recordFailure(java.util.UUID sessionId, java.util.UUID ownerToken,
                                                    String failureCode) { }
            };
            return new UploadService(transactions, new UploadInspector(),
                org.mockito.Mockito.mock(ObjectStorage.class),
                new StagingWaitPolicy(java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(1)),
                org.mockito.Mockito.mock(CleanupTaskRepository.class), new UploadFailureClassifier());
        }
    }
}
