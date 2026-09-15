package com.example.campusmarket.gateway;

import com.example.campusmarket.testsupport.HttpAssertions;
import com.example.campusmarket.testsupport.TestJwtFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway 冷启动依赖不可达时必须保持 DOWN，并拒绝所有受保护请求。 */
@SpringBootTest(classes = ApiGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.enabled=true",
        "eureka.client.register-with-eureka=true",
        "eureka.client.fetch-registry=true",
        "eureka.client.service-url.defaultZone=http://127.0.0.1:1/eureka/",
        "eureka.client.webclient.enabled=true",
        "eureka.client.restclient.enabled=false",
        "eureka.client.jersey.enabled=false",
        "eureka.client.registry-fetch-interval-seconds=1",
        "eureka.client.initial-instance-info-replication-interval-seconds=1",
        "spring.cloud.discovery.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/api/auth/.well-known/jwks.json",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://gateway.test",
        "campus.market.jwt.audience=campus-market-api",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.show-components=always",
        "management.endpoints.web.exposure.include=health,info,metrics",
        "management.endpoint.health.group.readiness.include=readinessState,jwks,eureka"
    })
class GatewayReadinessIT {
    private static final String ISSUER = "http://gateway.test";
    private static final String AUDIENCE = "campus-market-api";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void unavailableEurekaAndJwksKeepGatewayReadinessDown() throws Exception {
        HttpResponse<String> readiness = get("/actuator/health/readiness");

        assertThat(readiness.statusCode()).isEqualTo(503);
        JsonNode body = JSON.readTree(readiness.body());
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("components").path("jwks").path("status").asText())
            .as("JWKS must be one of the readiness causes")
            .isEqualTo("DOWN");
        assertThat(body.path("components").path("eureka").path("status").asText())
            .as("Eureka must be one of the readiness causes")
            .isEqualTo("DOWN");
    }

    @Test
    void unavailableJwksNeverLetsProtectedApiSucceedOnColdStart() throws Exception {
        String token = TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"),
            Instant.now().minusSeconds(1), TestJwtFactory.ACCESS_TTL,
            ISSUER, AUDIENCE, "test-key-1");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/listings"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
