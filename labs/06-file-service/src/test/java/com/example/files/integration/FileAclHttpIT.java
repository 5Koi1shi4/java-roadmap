package com.example.files.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Spring HTTP 验证 owner/grantee/stranger、统一 404 和管理员无旁路。 */
@SpringBootTest(classes = com.example.files.FileServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true", "file.storage.local-root=target/acl-http-storage",
        "spring.servlet.multipart.max-file-size=21MB", "spring.servlet.multipart.max-request-size=22MB"})
@ActiveProfiles("test")
class FileAclHttpIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void ownerAndGranteeReadButOnlyOwnerMutatesAndHiddenResponsesMatch() throws Exception {
        String fileId = upload(700L);
        HttpHeaders owner = headers(700L);
        HttpHeaders reader = headers(701L);
        HttpHeaders stranger = headers(799L);

        assertThat(client.exchange("/api/files/" + fileId, HttpMethod.GET, new HttpEntity<>(owner), String.class)
            .getStatusCode().value()).isEqualTo(200);
        assertThat(client.exchange("/api/files/" + fileId + "/grants/701", HttpMethod.PUT,
            new HttpEntity<>(owner), String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(client.exchange("/api/files/" + fileId, HttpMethod.GET, new HttpEntity<>(reader), String.class)
            .getStatusCode().value()).isEqualTo(200);
        assertThat(client.exchange("/api/files/" + fileId + "/grants/702", HttpMethod.PUT,
            new HttpEntity<>(reader), String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(client.exchange("/api/files/" + fileId, HttpMethod.DELETE, new HttpEntity<>(reader), String.class)
            .getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> denied = client.exchange("/api/files/" + fileId, HttpMethod.GET,
            new HttpEntity<>(stranger), String.class);
        String missingId = UUID.randomUUID().toString();
        ResponseEntity<String> missing = client.exchange("/api/files/" + missingId, HttpMethod.GET,
            new HttpEntity<>(stranger), String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(withoutCorrelation(denied.getBody())).isEqualTo(withoutCorrelation(missing.getBody()));

        assertThat(client.exchange("/api/files/" + fileId, HttpMethod.DELETE, new HttpEntity<>(owner), String.class)
            .getStatusCode().value()).isEqualTo(204);
        assertThat(client.exchange("/api/files/" + fileId, HttpMethod.GET, new HttpEntity<>(owner), String.class)
            .getStatusCode().value()).isEqualTo(404);
    }

    private String upload(long userId) throws Exception {
        HttpHeaders headers = headers(userId);
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "acl.pdf");
        ByteArrayResource resource = new ByteArrayResource("%PDF-1.7 acl".getBytes(StandardCharsets.US_ASCII)) {
            @Override public String getFilename() { return "acl.pdf"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, part));
        ResponseEntity<String> response = client.postForEntity("/api/files", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode node = objectMapper.readTree(response.getBody());
        return UUID.fromString(node.path("fileId").asText()).toString();
    }

    private static HttpHeaders headers(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Trusted-User-Id", Long.toString(userId));
        return headers;
    }

    private String withoutCorrelation(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("correlationId");
        return node.toString();
    }
}
