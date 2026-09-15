package com.example.campusmarket.product.integration;

import com.example.campusmarket.product.security.ProductResourceServerConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 冷启动身份 JWKS/Eureka 不可达时产品服务不可宣称就绪。 */
@SpringBootTest(classes = ProductReadinessIT.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://127.0.0.1:1/eureka/",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/api/auth/.well-known/jwks.json",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://gateway.test",
        "campus.market.jwt.audience=campus-market-api",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.show-components=always",
        "management.endpoint.health.group.readiness.include=readinessState,jwks,eureka",
        "management.endpoint.health.group.readiness.show-components=always"
    })
class ProductReadinessIT {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort private int port;

    @Test
    void unavailableJwksAndEurekaMakeReadinessDownWithInspectableComponents() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/actuator/health/readiness"))
            .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("components").path("jwks").path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("components").path("eureka").path("status").asText()).isEqualTo("DOWN");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import(ProductResourceServerConfiguration.class)
    static class TestApplication {
    }
}
