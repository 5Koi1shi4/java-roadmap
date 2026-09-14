package com.example.campusmarket.gateway;

import com.example.campusmarket.gateway.support.CloudApplicationCluster;
import com.example.campusmarket.testsupport.HttpAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过 Gateway 验证 Eureka、身份服务和兼容单体之间的真实 HTTP 路径。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudJourneyIT {
    private static final String PASSWORD = "correct horse battery staple";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudApplicationCluster cluster;

    @BeforeAll
    void startCluster() {
        cluster = CloudApplicationCluster.start();
    }

    @AfterAll
    void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void registersLogsInAndCreatesListingThroughGateway() throws Exception {
        String email = "用户" + UUID.randomUUID() + "@stu.example.edu.cn";

        HttpResponse<String> issued = post("/api/auth/email-verifications",
            "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}");
        assertThat(issued.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(issued);
        String code = cluster.latestVerificationCode(email);
        assertThat(code).matches("\\d{6}");

        HttpResponse<String> registered = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                + "\",\"code\":\"" + code + "\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        HttpAssertions.assertJsonUtf8(registered);

        HttpResponse<String> login = post("/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(login);
        JsonNode loginJson = mapper.readTree(login.body());
        String token = loginJson.path("accessToken").asText();
        UUID userId = UUID.fromString(loginJson.path("userId").asText());
        assertThat(token).isNotBlank();
        assertThat(loginJson.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(loginJson.path("expiresIn").asInt()).isEqualTo(900);
        assertThat(loginJson.path("roles").isArray()).isTrue();
        assertThat(loginJson.path("roles").size()).isEqualTo(1);
        assertThat(loginJson.path("roles").get(0).asText()).isEqualTo("ROLE_USER");

        HttpResponse<String> adminAttempt = postBearer(
            "/api/admin/warranty-cases/" + UUID.randomUUID() + "/decisions", token, "{}",
            Map.of("X-User-Id", UUID.randomUUID().toString(), "X-Internal-Role", "ROLE_ADMIN"));
        assertThat(adminAttempt.statusCode()).isEqualTo(403);
        HttpAssertions.assertJsonUtf8(adminAttempt);

        HttpResponse<String> listing = postBearer("/api/listings", token,
            "{\"title\":\"Java 并发编程\",\"description\":\"九成新\","
                + "\"category\":\"教材\",\"unitPriceFen\":5600,\"availableQuantity\":1}",
            Map.of("X-User-Id", UUID.randomUUID().toString(),
                "X-Internal-Role", "ROLE_ADMIN",
                "X-Correlation-Id", "client-selected"));
        assertThat(listing.statusCode()).isEqualTo(201);
        HttpAssertions.assertJsonUtf8(listing);
        JsonNode listingJson = mapper.readTree(listing.body());
        UUID listingId = UUID.fromString(listingJson.path("id").asText());
        assertThat(listingJson.path("title").asText()).isEqualTo("Java 并发编程");
        assertThat(listingJson.path("status").asText()).isEqualTo("DRAFT");
        assertThat(cluster.listingSellerId(listingId)).isEqualTo(userId);

        assertThat(cluster.registryApplications())
            .contains("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE", "API-GATEWAY");
        Map<String, Integer> webServerPorts = cluster.webServerPorts();
        Map<String, List<Integer>> registryPorts = cluster.registryInstancePorts();
        assertThat(registryPorts)
            .containsKeys("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE", "API-GATEWAY")
            .allSatisfy((name, ports) -> assertThat(ports).containsExactly(webServerPorts.get(name)));
        assertThat(webServerPorts.values()).allMatch(port -> port > 0 && port < 65536);
        assertThat(cluster.gatewayRouteUris())
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "identity-api", "lb://identity-service",
                "legacy-api", "lb://legacy-market-service"))
            .allSatisfy((id, uri) -> assertThat(uri).startsWith("lb://"));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postBearer(String path, String token, String body,
                                             Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return cluster.gatewayBaseUri().resolve(path);
    }
}
