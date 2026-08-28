package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.example.files.application.cleanup.StorageCleanupService;
import com.example.files.application.upload.BlobRepository;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.UploadSessionRepository;
import com.example.files.config.FileServiceProperties;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 HTTP、MySQL、MinIO 与 ACL/物理清理的安全工作流验收。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true", "file.storage.type=minio"})
@ActiveProfiles("test")
class SecureFileWorkflowIT extends SharedStorageContainers {
    @Autowired private TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcCleanupTaskRepository cleanupTasks;
    @Autowired private ObjectStorage storage;
    @Autowired private MinioObjectStorage minio;
    @Autowired private UploadSessionRepository sessions;
    @Autowired private BlobRepository blobs;
    @Autowired private FileServiceProperties properties;

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registerStorageProperties(registry);
    }

    @Test
    void isolatesSameContentAuthorizesThenRevokesAndPhysicallyCleansBlob() throws Exception {
        byte[] bytes = "%PDF-1.7\nsecure-workflow".getBytes(StandardCharsets.US_ASCII);
        JsonNode first = upload(601, "甲.pdf", bytes);
        JsonNode second = upload(602, "乙.pdf", bytes);
        String firstId = first.path("fileId").asText();
        String secondId = second.path("fileId").asText();
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(first.toString()).doesNotContain("hash", "blob", "objectKey", "dedup");

        assertThat(get(firstId, 602).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(firstId, 601).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(firstId + "/grants/602", HttpMethod.PUT, 601).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(firstId + "/content", 602).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> link = exchange(firstId + "/download-links?ttlSeconds=120", HttpMethod.POST, 602);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(link.getBody()).doesNotContain("sha", "blobId", "objectKey", "secret");
        assertThat(exchange(firstId + "/grants/602", HttpMethod.DELETE, 601).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(firstId + "/content", 602).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(firstId, 602).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Long blobId = jdbc.queryForObject("SELECT blob_id FROM stored_file WHERE file_id=?", Long.class, firstId);
        String objectKey = jdbc.queryForObject("SELECT object_key FROM stored_blob WHERE id=?", String.class, blobId);
        assertThat(storage.stat(objectKey).size()).isEqualTo(bytes.length);
        assertThat(exchange(firstId, HttpMethod.DELETE, 601).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(secondId, HttpMethod.DELETE, 602).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        StorageCleanupService cleaner = new StorageCleanupService(cleanupTasks, storage, sessions, blobs, properties);
        org.awaitility.Awaitility.await().untilAsserted(() -> {
            cleaner.runBatch("workflow-test-" + UUID.randomUUID());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_file WHERE file_id IN (?,?) AND status='ACTIVE'",
                Integer.class, firstId, secondId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE id=? AND status='DELETED'", Integer.class, blobId))
                .isOne();
        });
        assertThat(minio.exists(objectKey)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event WHERE file_id IN (?,?) AND (failure_code LIKE '%TMP%' OR failure_code LIKE '%BLOB%' OR client_trace_id LIKE '%X-Amz%')",
            Integer.class, firstId, secondId)).isZero();
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

    private ResponseEntity<String> get(String path, long user) {
        return client.exchange("/api/files/" + path, HttpMethod.GET, new HttpEntity<>(headers(user)), String.class);
    }
    private ResponseEntity<String> exchange(String path, HttpMethod method, long user) {
        return client.exchange("/api/files/" + path, method, new HttpEntity<>(headers(user)), String.class);
    }
    private static HttpHeaders headers(long user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", Long.toString(user));
        return headers;
    }
}
