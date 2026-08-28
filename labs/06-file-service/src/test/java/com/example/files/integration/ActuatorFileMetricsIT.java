package com.example.files.integration;

import com.example.files.FileServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Spring Actuator HTTP 端点验证文件指标 wiring 和低基数标签。 */
@SpringBootTest(classes = FileServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.identity.trusted-header-enabled=true", "file.storage.local-root=target/metrics-it-storage",
        "management.endpoints.web.exposure.include=health,info,metrics",
        "management.endpoint.metrics.access=unrestricted"})
@ActiveProfiles("test")
class ActuatorFileMetricsIT extends SharedMySqlContainer {
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate client;

    @Test
    void exposesFixedFileMetersThroughRealActuatorHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trusted-User-Id", "42");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDispositionFormData("file", "指标.pdf");
        body.add("file", new HttpEntity<>(new ByteArrayResource("%PDF-1.7\nmetrics".getBytes(StandardCharsets.US_ASCII)) {
            @Override public String getFilename() { return "指标.pdf"; }
        }, part));
        assertThat(client.postForEntity("/api/files", new HttpEntity<>(body, headers), String.class)
            .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> meter = client.getForEntity("/actuator/metrics/file.upload.total", String.class);
        assertThat(meter.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meter.getBody()).contains("file.upload.total", "result", "success");
        assertThat(meter.getBody()).doesNotContain("userId", "fileId", "hash", "objectKey", "tempKey", "correlationId");

        ResponseEntity<String> names = client.getForEntity("/actuator/metrics", String.class);
        assertThat(names.getBody()).contains("file.upload.total", "file.download.total", "file.acl.total",
            "file.storage.operation.duration");
    }
}
