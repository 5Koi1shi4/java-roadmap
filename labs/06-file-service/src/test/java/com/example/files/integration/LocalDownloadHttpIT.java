package com.example.files.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Spring HTTP 验证本地流式下载、文件名头和撤权即时失效。 */
@SpringBootTest(classes = com.example.files.FileServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true",
        "file.download.local-hmac-secret=01234567890123456789012345678901",
        "file.storage.local-root=target/download-http-storage"})
@ActiveProfiles("test")
class LocalDownloadHttpIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void streamsContentWithSafeHeadersAndRevokedLinkStopsWorking() throws Exception {
        String fileId = upload(810L);
        ResponseEntity<byte[]> content = client.exchange("/api/files/" + fileId + "/content", HttpMethod.GET,
            new HttpEntity<>(headers(810L)), byte[].class);
        assertThat(content.getStatusCode().value()).isEqualTo(200);
        assertThat(content.getBody()).containsExactly("%PDF-1.7 streamed".getBytes(StandardCharsets.US_ASCII));
        assertThat(content.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(content.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(content.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("filename=\"download.pdf\"").contains("filename*=UTF-8''%E8%B5%84%E6%96%99.pdf");

        ResponseEntity<String> linkResponse = client.exchange("/api/files/" + fileId + "/download-links",
            HttpMethod.POST, new HttpEntity<>(headers(810L)), String.class);
        assertThat(linkResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode link = objectMapper.readTree(linkResponse.getBody());
        String path = link.path("url").asText();
        assertThat(path).startsWith("/api/local-downloads/").doesNotContain("blobs/");
        ResponseEntity<byte[]> linked = client.exchange(path, HttpMethod.GET,
            new HttpEntity<>(headers(810L)), byte[].class);
        assertThat(linked.getStatusCode().value()).isEqualTo(200);

        assertThat(client.exchange("/api/files/" + fileId + "/grants/811", HttpMethod.PUT,
            new HttpEntity<>(headers(810L)), String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(client.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(811L)), byte[].class)
            .getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> readerLinkResponse = client.exchange("/api/files/" + fileId + "/download-links",
            HttpMethod.POST, new HttpEntity<>(headers(811L)), String.class);
        String readerPath = objectMapper.readTree(readerLinkResponse.getBody()).path("url").asText();
        assertThat(client.exchange(readerPath, HttpMethod.GET, new HttpEntity<>(headers(811L)), byte[].class)
            .getStatusCode().value()).isEqualTo(200);
        assertThat(client.exchange("/api/files/" + fileId + "/grants/811", HttpMethod.DELETE,
            new HttpEntity<>(headers(810L)), String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(client.exchange(readerPath, HttpMethod.GET, new HttpEntity<>(headers(811L)), byte[].class)
            .getStatusCode().value()).isEqualTo(404);
    }

    private String upload(long userId) throws Exception {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "资料.pdf");
        byte[] bytes = "%PDF-1.7 streamed".getBytes(StandardCharsets.US_ASCII);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "资料.pdf"; }
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
}
