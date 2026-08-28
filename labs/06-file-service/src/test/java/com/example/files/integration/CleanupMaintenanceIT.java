package com.example.files.integration;

import com.example.files.config.FileServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 维护配置的安全边界回归。 */
@SpringBootTest(classes = com.example.files.FileServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"file.maintenance.enabled=true", "file.identity.trusted-header-enabled=true",
        "file.storage.local-root=target/maintenance-http-storage"})
@ActiveProfiles("test")
class CleanupMaintenanceIT extends SharedMySqlContainer {
    @Autowired private TestRestTemplate client;

    @Test void cleanupScheduleIsPositiveAndTyped() {
        FileServiceProperties.Cleanup cleanup = new FileServiceProperties.Cleanup(50, Duration.ofSeconds(30),
            List.of(Duration.ofSeconds(5)), 5, Duration.ofHours(24), Duration.ofMinutes(1));
        assertThat(cleanup.schedule()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test void enabledTestProfileExposesOnlyAuthenticatedMaintenanceRoute() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unauthenticated = client.exchange(
            "/api/admin/storage-cleanups/00000000-0000-0000-0000-000000000001/retry",
            HttpMethod.POST, new HttpEntity<>("{}", json), String.class);
        assertThat(unauthenticated.getStatusCode().value()).as("body=%s", unauthenticated.getBody()).isEqualTo(401);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trusted-User-Id", "1");
        ResponseEntity<String> authenticated = client.exchange(
            "/api/admin/storage-cleanups/00000000-0000-0000-0000-000000000001/retry",
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(authenticated.getStatusCode().value()).isEqualTo(503);
        json.putAll(headers);
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unknownFields = client.exchange(
            "/api/admin/storage-cleanups/00000000-0000-0000-0000-000000000001/retry",
            HttpMethod.POST, new HttpEntity<>("{\"objectKey\":\"blobs/secret\",\"attempt\":99}", json), String.class);
        assertThat(unknownFields.getStatusCode().value()).isEqualTo(400);
    }
}
