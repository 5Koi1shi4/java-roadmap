package com.example.campusmarket.gateway;

import com.example.campusmarket.testsupport.TestJwtFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/** 通过真实 Gateway WebFlux 链验证 AI 路由的路径和方法边界。 */
@SpringBootTest(
    classes = ApiGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "campus.market.jwt.issuer=http://gateway.test",
        "campus.market.jwt.audience=campus-market-api",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://gateway.test"
    })
class SupportRouteIT {
    private static final String AI_PATH = "/api/ai/support/answers";
    private static final AtomicInteger AI_HITS = new AtomicInteger();
    private static final DisposableServer AI_BACKEND = startBackend(true);
    private static final DisposableServer FALLBACK_BACKEND = startBackend(false);

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> fallbackUri() + "/jwks");
        route(registry, 0, "ai-support-answer", aiUri(), AI_PATH, "POST");
        route(registry, 1, "identity-api", fallbackUri(), "/api/auth/**", null);
        route(registry, 2, "product-search", fallbackUri(), "/api/search", "GET");
        route(registry, 3, "product-listing-search", fallbackUri(),
            "/api/listings/search", "GET");
        route(registry, 4, "legacy-api", fallbackUri(), "/api/**", null);
    }

    @BeforeEach
    void resetHits() {
        AI_HITS.set(0);
    }

    @AfterAll
    static void stopBackends() {
        AI_BACKEND.disposeNow();
        FALLBACK_BACKEND.disposeNow();
    }

    @Test
    void forwardsOnlyExactPostToAiBackend() {
        client().post().uri(AI_PATH)
            .contentType(APPLICATION_JSON)
            .bodyValue("{\"question\":\"退款规则\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.ai").isEqualTo(true);
        assertThat(AI_HITS).hasValue(1);

        client().get().uri(AI_PATH)
            .header(AUTHORIZATION, "Bearer " + validToken())
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.ai").isEqualTo(false);

        client().post().uri(AI_PATH + "/extra")
            .header(AUTHORIZATION, "Bearer " + validToken())
            .contentType(APPLICATION_JSON)
            .bodyValue("{\"question\":\"退款规则\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.ai").isEqualTo(false);
        assertThat(AI_HITS).hasValue(1);
    }

    @Test
    void invalidBearerIsUnauthorizedEvenForPublicAiPost() {
        client().post().uri(AI_PATH)
            .header(AUTHORIZATION, "Bearer invalid-token")
            .contentType(APPLICATION_JSON)
            .bodyValue("{\"question\":\"退款规则\"}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AI_HITS).hasValue(0);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String validToken() {
        return TestJwtFactory.issue(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Set.of("ROLE_USER"), Instant.now().minusSeconds(1), Duration.ofMinutes(15),
            "http://gateway.test", "campus-market-api", "test-key-1");
    }

    private static void route(DynamicPropertyRegistry registry, int index, String id,
                              String uri, String path, String method) {
        registry.add("spring.cloud.gateway.server.webflux.routes[" + index + "].id", () -> id);
        registry.add("spring.cloud.gateway.server.webflux.routes[" + index + "].uri", () -> uri);
        registry.add("spring.cloud.gateway.server.webflux.routes[" + index + "].predicates[0]",
            () -> "Path=" + path);
        if (method != null) {
            registry.add("spring.cloud.gateway.server.webflux.routes[" + index + "].predicates[1]",
                () -> "Method=" + method);
        }
    }

    private static DisposableServer startBackend(boolean ai) {
        return HttpServer.create()
            .port(0)
            .handle((request, response) -> {
                if (request.path().endsWith("jwks")) {
                    String publicJwk = TestJwtFactory.publicJwk("test-key-1").toJSONString();
                    return response.header("Content-Type", "application/json; charset=UTF-8")
                        .sendString(Mono.just("{\"keys\":[" + publicJwk + "]}"));
                }
                if (ai) {
                    AI_HITS.incrementAndGet();
                }
                String body = ai ? "{\"ai\":true}" : "{\"ai\":false}";
                return response.header("Content-Type", "application/json; charset=UTF-8")
                    .sendString(Mono.just(body));
            })
            .bindNow();
    }

    private static String aiUri() {
        return "http://localhost:" + AI_BACKEND.port();
    }

    private static String fallbackUri() {
        return "http://localhost:" + FALLBACK_BACKEND.port();
    }
}
