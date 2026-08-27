package com.example.files.integration;

import com.example.files.FileServiceApplication;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Spring MVC/Servlet、Flyway、JDBC 与本地对象存储的上传链路验收。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "file.identity.trusted-header-enabled=true",
        "file.storage.local-root=target/http-it-storage",
        "spring.servlet.multipart.max-file-size=21MB",
        "spring.servlet.multipart.max-request-size=22MB"
    })
@ActiveProfiles("test")
class FileUploadHttpIT extends SharedMySqlContainer {
    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate client;

    @Test
    void missingIdentityIs401AndResponseIsUtf8() {
        ResponseEntity<String> response = post("资料.pdf", "application/pdf", pdfBytes(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getBody()).contains("未认证");
    }

    @Test
    void duplicateIdentityHeaderIs401() {
        HttpHeaders headers = baseHeaders();
        headers.add("X-Trusted-User-Id", "42");
        headers.add("X-Trusted-User-Id", "43");
        ResponseEntity<String> response = postWithHeaders("资料.pdf", "application/pdf", pdfBytes(), headers);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void acceptsJpegPngWebpAndPdfAndAllowsMissingExtension() {
        Map<String, byte[]> payloads = Map.of(
            "照片.jpg", jpegBytes(), "图像.png", pngBytes(), "动画.webp", webpBytes(), "课程资料.pdf", pdfBytes(),
            "无扩展名", pdfBytes());
        Map<String, String> types = Map.of(
            "照片.jpg", "image/jpeg", "图像.png", "image/png", "动画.webp", "image/webp",
            "课程资料.pdf", "application/pdf", "无扩展名", "application/pdf");
        payloads.forEach((name, bytes) -> {
            ResponseEntity<String> response = post(name, types.get(name), bytes, "42");
            assertThat(response.getStatusCode().value()).as(name).isEqualTo(201);
            assertThat(response.getHeaders().getContentType().toString()).as(name)
                .isEqualTo("application/json;charset=UTF-8");
            assertThat(response.getBody()).as(name).contains("displayName", "mediaType", "size", "createdAt")
                .doesNotContain("hash", "blob", "objectKey", "dedup", "deduplication");
            if (name.equals("课程资料.pdf")) assertThat(response.getBody()).contains("课程资料.pdf");
        });
    }

    @Test
    void rejectsDeclaredTypeAndExtensionConflicts() {
        assertThat(post("资料.pdf", "image/png", pdfBytes(), "42").getStatusCode().value()).isEqualTo(400);
        assertThat(post("资料.png", "application/pdf", pdfBytes(), "42").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void exactTwentyMiBSucceedsAndOneByteMoreIs413() {
        byte[] exact = new byte[20 * 1024 * 1024];
        exact[0] = '%'; exact[1] = 'P'; exact[2] = 'D'; exact[3] = 'F'; exact[4] = '-';
        assertThat(post("边界.pdf", "application/pdf", exact, "42").getStatusCode().value()).isEqualTo(201);
        byte[] tooLarge = java.util.Arrays.copyOf(exact, exact.length + 1);
        assertThat(post("超限.pdf", "application/pdf", tooLarge, "42").getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void sameContentCreatesTwoLogicalFilesWithoutPhysicalDetails() {
        byte[] bytes = pdfBytes();
        ResponseEntity<String> first = post("甲.pdf", "application/pdf", bytes, "42");
        ResponseEntity<String> second = post("乙.pdf", "application/pdf", bytes, "43");
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody()).contains("甲.pdf").doesNotContain("hash", "blob", "objectKey", "dedup");
        assertThat(second.getBody()).contains("乙.pdf").doesNotContain("hash", "blob", "objectKey", "dedup");
        assertThat(first.getBody()).isNotEqualTo(second.getBody());
    }

    private ResponseEntity<String> post(String name, String declaredType, byte[] bytes, String userId) {
        HttpHeaders headers = baseHeaders();
        if (userId != null) headers.set("X-Trusted-User-Id", userId);
        return postWithHeaders(name, declaredType, bytes, headers);
    }

    private ResponseEntity<String> postWithHeaders(String name, String declaredType, byte[] bytes, HttpHeaders headers) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(declaredType));
        partHeaders.setContentDispositionFormData("file", name);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        return client.exchange("/api/files", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static byte[] pdfBytes() { return "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII); }
    private static byte[] jpegBytes() { return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}; }
    private static byte[] pngBytes() { return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}; }
    private static byte[] webpBytes() { return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}; }
}
