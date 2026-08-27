package com.example.files.integration;

import com.example.files.FileServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 HTTP 兑换令牌拒绝矩阵：所有非法凭证统一隐藏且写入安全审计。 */
@SpringBootTest(classes = FileServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true",
        "file.download.local-hmac-secret=01234567890123456789012345678901",
        "file.storage.local-root=target/download-token-http-storage"})
@ActiveProfiles("test")
class DownloadTokenDeniedHttpIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void allMalformedTokenFormsAreSame404AndAuditedWithoutToken() throws Exception {
        String fileId = upload(850L);
        String valid = issue(850L, fileId, 30);
        String expired = issue(850L, fileId, 1);
        String[] parts = valid.split("\\.", -1);
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String unknownVersion = encode(payload.replaceFirst("^1\\|", "2|") ) + "." + parts[1];
        String nonCanonical = parts[0] + "A." + parts[1];
        String changedMac = parts[1].endsWith("A") ? parts[1].substring(0, parts[1].length() - 1) + "B"
            : parts[1].substring(0, parts[1].length() - 1) + "A";
        String tampered = parts[0] + "." + changedMac;
        Thread.sleep(1_250L);

        ResponseEntity<String> missing = client.exchange("/api/files/" + java.util.UUID.randomUUID() + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(850L)), String.class);
        String semanticMissing = withoutCorrelation(missing.getBody());
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        for (String token : List.of("malformed", unknownVersion, nonCanonical, tampered, expired)) {
            assertDenied(token, 850L, semanticMissing);
        }
        // A valid token redeemed by a different actor is denied before ACL and is equally hidden.
        assertDenied(valid, 851L, semanticMissing);
    }

    private void assertDenied(String token, long actorId, String semanticMissing) throws Exception {
        long before = deniedCount();
        ResponseEntity<String> response = client.exchange("/api/local-downloads/" + token, HttpMethod.GET,
            new HttpEntity<>(headers(actorId)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(withoutCorrelation(response.getBody())).isEqualTo(semanticMissing);
        assertThat(response.getBody()).doesNotContain(token).doesNotContain("nonce");
        assertThat(deniedCount()).isEqualTo(before + 1);
        assertThat(jdbc.queryForList("SELECT failure_code FROM file_audit_event "
            + "WHERE action='DOWNLOAD_TOKEN_DENIED' ORDER BY id DESC LIMIT 1", String.class))
            .contains("INVALID_TOKEN");
    }

    private String issue(long userId, String fileId, int ttlSeconds) throws Exception {
        HttpHeaders requestHeaders = headers(userId);
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = client.exchange("/api/files/" + fileId
                + "/download-links?ttlSeconds=" + ttlSeconds, HttpMethod.POST,
            new HttpEntity<>(null, requestHeaders), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getBody());
        return body.path("url").asText().substring("/api/local-downloads/".length());
    }

    private String upload(long userId) throws Exception {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "token.pdf");
        org.springframework.core.io.ByteArrayResource resource =
            new org.springframework.core.io.ByteArrayResource("%PDF-1.7 token".getBytes(StandardCharsets.US_ASCII)) {
                @Override public String getFilename() { return "token.pdf"; }
            };
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, part));
        ResponseEntity<String> response = client.postForEntity("/api/files",
            new HttpEntity<>(body, headers(userId)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return objectMapper.readTree(response.getBody()).path("fileId").asText();
    }

    private long deniedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_audit_event WHERE action='DOWNLOAD_TOKEN_DENIED'",
            Long.class);
    }

    private String withoutCorrelation(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("correlationId");
        return node.toString();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpHeaders headers(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", Long.toString(userId));
        return headers;
    }
}
