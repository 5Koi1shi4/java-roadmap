package com.example.campusmarket.gateway;

import com.example.campusmarket.testsupport.HttpAssertions;
import com.example.campusmarket.testsupport.TestJwtFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/** 验证 Nimbus 未知 kid 只刷新一次，且 Gateway 认证/路由指标保持低基数。 */
@SpringBootTest(
    classes = ApiGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "campus.market.jwt.issuer=http://gateway.test",
        "campus.market.jwt.audience=campus-market-api",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://gateway.test",
        "management.endpoint.health.show-components=always"
    })
class JwtJwksRefreshIT {
    private static final String ISSUER = "http://gateway.test";
    private static final String AUDIENCE = "campus-market-api";
    private static final String KNOWN_KID = "test-key-1";
    private static final String UNKNOWN_KID = "unknown-key";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final AtomicInteger JWKS_REQUESTS = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer JWKS_SERVER = startJwksServer();
    private static final DisposableServer BACKEND = startBackend();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry metrics;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "identity-api");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", JwtJwksRefreshIT::backendUri);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]",
            () -> "Path=/api/auth/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "legacy-api");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri", JwtJwksRefreshIT::backendUri);
        registry.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]",
            () -> "Path=/api/**");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JwtJwksRefreshIT::jwksUri);
    }

    @AfterAll
    static void stopServers() {
        JWKS_SERVER.stop(0);
        BACKEND.disposeNow();
    }

    @Test
    void unknownKidCausesExactlyOneJwksRefreshAndReturns401() throws Exception {
        double validBefore = counter("valid");
        HttpResponse<String> warm = get(token(KNOWN_KID));
        assertThat(warm.statusCode()).isEqualTo(200);
        int beforeUnknownKid = JWKS_REQUESTS.get();

        HttpResponse<String> rejected = get(token(UNKNOWN_KID));

        assertThat(rejected.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(rejected);
        assertThat(JWKS_REQUESTS.get())
            .as("one unknown kid must cause one cache refresh, with no recursive retry")
            .isEqualTo(beforeUnknownKid + 1);
        assertThat(counter("unknown_kid")).isGreaterThanOrEqualTo(1.0);
        assertThat(counter("valid") - validBefore).isEqualTo(1.0);
    }

    @Test
    void invalidJwtAndRouteMetricsUseOnlyFixedLowCardinalityTags() throws Exception {
        double validBefore = counter("valid");
        double routeSuccessBefore = routeCounter("legacy-api", "success");
        HttpResponse<String> warm = get(token(KNOWN_KID));
        assertThat(warm.statusCode()).isEqualTo(200);
        assertThat(counter("valid") - validBefore).isEqualTo(1.0);
        assertThat(routeCounter("legacy-api", "success") - routeSuccessBefore).isEqualTo(1.0);

        double invalidBefore = counter("invalid");
        double unauthorizedBefore = routeCounter("legacy-api", "unauthorized");
        String invalid = tamper(token(KNOWN_KID));

        HttpResponse<String> rejected = get(invalid);

        assertThat(rejected.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(rejected);
        assertThat(counter("invalid") - invalidBefore).isEqualTo(1.0);
        assertThat(routeCounter("legacy-api", "unauthorized") - unauthorizedBefore).isEqualTo(1.0);

        List<Meter> jwtMeters = metrics.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("campus.gateway.jwt"))
            .toList();
        assertThat(jwtMeters).isNotEmpty().allSatisfy(meter -> {
            assertThat(meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactly("result");
            assertThat(meter.getId().getTag("result"))
                .isIn("valid", "invalid", "unknown_kid");
        });

        List<Meter> routeMeters = metrics.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("campus.gateway.route"))
            .toList();
        assertThat(routeMeters).isNotEmpty().allSatisfy(meter -> {
            assertThat(meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("route", "result");
            assertThat(meter.getId().getTag("route"))
                .isIn("identity-api", "legacy-api", "unmatched");
            assertThat(meter.getId().getTag("result"))
                .isIn("success", "unauthorized", "forbidden", "dependency_unavailable", "client_error");
        });
    }

    private double counter(String result) {
        var meter = metrics.find("campus.gateway.jwt").tag("result", result).counter();
        return meter == null ? 0.0 : meter.count();
    }

    private double routeCounter(String route, String result) {
        var meter = metrics.find("campus.gateway.route")
            .tag("route", route).tag("result", result).counter();
        return meter == null ? 0.0 : meter.count();
    }

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/listings"))
            .timeout(Duration.ofSeconds(5))
            .header(AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String token(String kid) {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now().minusSeconds(1),
            TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, kid);
    }

    private static String tamper(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }

    private static String backendUri() {
        return "http://127.0.0.1:" + BACKEND.port();
    }

    private static String jwksUri() {
        return "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks";
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String body = JSON.writeValueAsString(Map.of(
                "keys", List.of(TestJwtFactory.publicJwk(KNOWN_KID).toJSONObject())));
            server.createContext("/jwks", exchange -> {
                JWKS_REQUESTS.incrementAndGet();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException("无法启动 JWKS 计数夹具", ex);
        }
    }

    private static DisposableServer startBackend() {
        return reactor.netty.http.server.HttpServer.create()
            .port(0)
            .handle((request, response) -> response
                .header("Content-Type", "application/json; charset=UTF-8")
                .sendString(Mono.just("{\"ok\":true}")))
            .bindNow();
    }
}
