package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Spring RANDOM_PORT、MySQL、MinIO 和代理端点的下载链路验收。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true", "file.storage.type=minio"})
@ActiveProfiles("test")
class MinioDownloadHttpIT extends SharedStorageContainers {
    @Autowired private TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private MeterRegistry metrics;

    @DynamicPropertySource
    static void registerMinioProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("file.storage.type", () -> "minio");
        registry.add("file.storage.minio-endpoint", SharedStorageContainers::minioEndpoint);
        registry.add("file.storage.minio-access-key", () -> ACCESS_KEY);
        registry.add("file.storage.minio-secret-key", () -> SECRET_KEY);
        registry.add("file.storage.minio-bucket", () -> BUCKET);
        registry.add("file.storage.minio-region", () -> "us-east-1");
    }

    @Test
    void minioBeanUploadsAuditsAndServesPresignedBytesThroughProxy() throws Exception {
        byte[] bytes = "%PDF-1.7\nproxy-download".getBytes(StandardCharsets.US_ASCII);
        JsonNode upload = upload(77, "资料.pdf", bytes);
        String fileId = upload.path("fileId").asText();
        double linkSuccessBefore = metricCount("link", "success");
        assertThat(context.getBeansOfType(com.example.files.infrastructure.storage.MinioObjectStorage.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.example.files.infrastructure.storage.LocalObjectStorage.class)).isEmpty();
        assertThat(context.getBeansOfType(com.example.files.application.upload.ObjectStorage.class)).hasSize(1);

        ResponseEntity<String> linkResponse = client.exchange("/api/files/" + fileId + "/download-links?ttlSeconds=120",
            HttpMethod.POST, new HttpEntity<>(headers(77)), String.class);
        assertThat(linkResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode link = objectMapper.readTree(linkResponse.getBody());
        String url = link.path("url").asText();
        assertThat(url).startsWith(minioEndpoint());
        assertThat(url).doesNotContain(SECRET_KEY);
        assertThat(java.time.Instant.parse(link.path("expiresAt").asText()))
            .isBetween(java.time.Instant.now().minusSeconds(2), java.time.Instant.now().plusSeconds(121));
        java.net.http.HttpResponse<byte[]> downloaded = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.body()).containsExactly(bytes);
        assertThat(downloaded.headers().firstValue("content-type").orElse("")).startsWith("application/pdf");
        assertThat(downloaded.headers().firstValue("content-disposition").orElse(""))
            .contains("filename*=UTF-8''");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event WHERE action='DOWNLOAD_LINK_ISSUED' AND result='SUCCESS'", Integer.class))
            .isGreaterThanOrEqualTo(1);
        assertThat(metricCount("link", "success")).isGreaterThan(linkSuccessBefore);
    }

    @Test
    void deniedLinkRecordsDeniedMetricAfterCommittedAccessAudit() throws Exception {
        String fileId = upload(78, "denied-link.pdf", "%PDF-1.7\ndenied-link".getBytes(StandardCharsets.US_ASCII))
            .path("fileId").asText();
        double deniedBefore = metricCount("link", "denied");

        ResponseEntity<String> response = client.exchange("/api/files/" + fileId
                + "/download-links?ttlSeconds=30", HttpMethod.POST,
            new HttpEntity<>(headers(79)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(metricCount("link", "denied")).isGreaterThan(deniedBefore);
    }

    @Test
    void contentEndpointUsesCurrentAcl() throws Exception {
        JsonNode upload = upload(88, "current.pdf", "%PDF-1.7\nacl".getBytes(StandardCharsets.US_ASCII));
        String fileId = upload.path("fileId").asText();
        ResponseEntity<byte[]> owner = client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(88)), byte[].class);
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Void> grant = client.exchange("/api/files/" + fileId + "/grants/89", HttpMethod.PUT,
            new HttpEntity<>(headers(88)), Void.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<byte[]> grantee = client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(89)), byte[].class);
        assertThat(grantee.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Void> revoke = client.exchange("/api/files/" + fileId + "/grants/89", HttpMethod.DELETE,
            new HttpEntity<>(headers(88)), Void.class);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(89)), byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private JsonNode upload(long user, String filename, byte[] bytes) throws Exception {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", filename);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, part));
        ResponseEntity<String> response = client.postForEntity("/api/files", new HttpEntity<>(body, headers(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private static HttpHeaders headers(long user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", Long.toString(user));
        return headers;
    }

    private double metricCount(String phase, String result) {
        return metrics.get("file.download.total").tags("phase", phase, "result", result).counter().count();
    }
}
