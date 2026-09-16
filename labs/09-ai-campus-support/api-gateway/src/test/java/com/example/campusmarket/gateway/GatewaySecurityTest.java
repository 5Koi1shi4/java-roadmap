package com.example.campusmarket.gateway;

import com.example.campusmarket.testsupport.TestJwtFactory;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/** 使用真实 WebFlux 链验证 Gateway 的认证边界和匿名白名单。 */
@SpringBootTest(
    classes = ApiGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "campus.market.jwt.issuer=http://gateway.test",
        "campus.market.jwt.audience=campus-market-api",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://gateway.test"
    })
class GatewaySecurityTest {
    private static final String JWKS_PATH = "/api/auth/.well-known/jwks.json";
    private static final MediaType JSON_UTF8 = new MediaType(
        "application", "json", StandardCharsets.UTF_8);
    private static final DisposableServer BACKEND = startBackend();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id",
            () -> "identity-api");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri",
            () -> backendUri());
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]",
            () -> "Path=/api/auth/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].id",
            () -> "legacy-api");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri",
            () -> backendUri());
        registry.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]",
            () -> "Path=/api/**");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> backendUri() + JWKS_PATH);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.disposeNow();
    }

    @Test
    void anonymousBusinessRequestReturnsUtf8401() {
        client().get().uri("/api/listings").exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().contentType(JSON_UTF8)
            .expectBody()
            .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
            .jsonPath("$.message").isEqualTo("未认证")
            .jsonPath("$.correlationId").value(value -> {
                assertThat(value).isInstanceOf(String.class)
                    .asString().matches("[0-9a-f-]{36}");
            });
    }

    @Test
    void anonymousIdentityAndJwksRequestsAreForwarded() {
        String forwardedToken = validToken(Set.of("ROLE_USER"));
        client().post().uri("/api/auth/login")
            .contentType(APPLICATION_JSON)
            .header(AUTHORIZATION, "Bearer " + forwardedToken)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.forwarded").isEqualTo(true)
            .jsonPath("$.authorization").isEqualTo("Bearer " + forwardedToken);

        client().get().uri(JWKS_PATH).exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(JSON_UTF8)
            .expectBody().jsonPath("$.keys[0].kid").isEqualTo("test-key-1");
    }

    @Test
    void validRegularUserCannotAccessAdminPath() {
        String token = TestJwtFactory.issue(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Set.of("ROLE_USER"),
            Instant.now().minusSeconds(1),
            Duration.ofMinutes(15),
            "http://gateway.test",
            "campus-market-api",
            "test-key-1");

        client().get().uri("/api/admin/settlements")
            .header(AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().contentType(JSON_UTF8)
            .expectBody()
            .jsonPath("$.code").isEqualTo("FORBIDDEN")
            .jsonPath("$.message").isEqualTo("无权访问");
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidContractTokens")
    void rejectsTokensOutsideTheFixedJwtContract(String ignoredName, String token) {
        client().get().uri("/api/listings")
            .header(AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().contentType(JSON_UTF8)
            .expectBody()
            .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
            .jsonPath("$.message").isEqualTo("未认证");
    }

    @Test
    void dependencyFailureReturnsSafeUtf8ServiceUnavailable() {
        String token = validToken(Set.of("ROLE_USER"));

        client().get().uri("/api/unavailable")
            .header(AUTHORIZATION, "Bearer " + token)
            .header("X-Correlation-Id", "client-selected")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectHeader().contentType(JSON_UTF8)
            .expectBody()
            .jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
            .jsonPath("$.message").isEqualTo("依赖服务暂时不可用")
            .jsonPath("$.correlationId").value(value ->
                assertThat(value).asString().matches("[0-9a-f-]{36}"));
    }

    @Test
    void downstreamServerErrorIsSanitizedToSafeUtf8ServiceUnavailable() {
        client().get().uri("/api/downstream-failure")
            .header(AUTHORIZATION, "Bearer " + validToken(Set.of("ROLE_USER")))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectHeader().contentType(JSON_UTF8)
            .expectBody()
            .jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
            .jsonPath("$.message").isEqualTo("依赖服务暂时不可用")
            .jsonPath("$.secret").doesNotExist();
    }

    private static Stream<Arguments> invalidContractTokens() {
        Instant issuedAt = Instant.now().minusSeconds(1);
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return Stream.of(
            Arguments.of("issuer", TestJwtFactory.issue(userId, Set.of("ROLE_USER"), issuedAt,
                Duration.ofMinutes(15), "http://other-issuer.test", "campus-market-api", "test-key-1")),
            Arguments.of("audience", TestJwtFactory.issue(userId, Set.of("ROLE_USER"), issuedAt,
                Duration.ofMinutes(15), "http://gateway.test", "other-audience", "test-key-1")),
            Arguments.of("unknown kid", TestJwtFactory.issue(userId, Set.of("ROLE_USER"), issuedAt,
                Duration.ofMinutes(15), "http://gateway.test", "campus-market-api", "unknown-key")),
            Arguments.of("algorithm", signedToken(JWSAlgorithm.RS384, "test-key-1",
                "http://gateway.test", List.of("campus-market-api"), userId.toString(),
                List.of("ROLE_USER"), issuedAt, issuedAt.plus(Duration.ofMinutes(15)))),
            Arguments.of("audience list", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api", "other-audience"), userId.toString(), List.of("ROLE_USER"),
                issuedAt, issuedAt.plus(Duration.ofMinutes(15)))),
            Arguments.of("noncanonical subject", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), "not-a-uuid", List.of("ROLE_USER"), issuedAt,
                issuedAt.plus(Duration.ofMinutes(15)))),
            Arguments.of("invalid role", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), List.of("ROLE_GUEST"), issuedAt,
                issuedAt.plus(Duration.ofMinutes(15)))),
            Arguments.of("missing roles", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), null, issuedAt,
                issuedAt.plus(Duration.ofMinutes(15)))),
            Arguments.of("fourteen minute lifetime", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), List.of("ROLE_USER"), issuedAt,
                issuedAt.plus(Duration.ofMinutes(14)))),
            Arguments.of("future issued at", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), List.of("ROLE_USER"),
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(930))),
            Arguments.of("missing issued at", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), List.of("ROLE_USER"), null,
                issuedAt.plus(Duration.ofMinutes(15)))),
            Arguments.of("missing expiration", signedToken("test-key-1", "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), List.of("ROLE_USER"), issuedAt,
                null)),
            Arguments.of("missing kid", signedToken(null, "http://gateway.test",
                List.of("campus-market-api"), userId.toString(), List.of("ROLE_USER"), issuedAt,
                issuedAt.plus(Duration.ofMinutes(15)))));
    }

    private static String validToken(Set<String> roles) {
        return TestJwtFactory.issue(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), roles,
            Instant.now().minusSeconds(1), Duration.ofMinutes(15), "http://gateway.test",
            "campus-market-api", "test-key-1");
    }

    private static String signedToken(String kid, String issuer, List<String> audience,
                                      String subject, Object roles, Instant issuedAt,
                                      Instant expiresAt) {
        return signedToken(JWSAlgorithm.RS256, kid, issuer, audience, subject, roles,
            issuedAt, expiresAt);
    }

    private static String signedToken(JWSAlgorithm algorithm, String kid, String issuer,
                                      List<String> audience, String subject, Object roles,
                                      Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject);
        if (issuedAt != null) {
            claims.issueTime(Date.from(issuedAt));
        }
        if (expiresAt != null) {
            claims.expirationTime(Date.from(expiresAt));
        }
        if (roles != null) {
            claims.claim("roles", roles);
        }
        JWSHeader.Builder header = new JWSHeader.Builder(algorithm)
            .type(JOSEObjectType.JWT);
        if (kid != null) {
            header.keyID(kid);
        }
        SignedJWT jwt = new SignedJWT(header.build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(TestRsaKeys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("测试 JWT 签发失败", ex);
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String backendUri() {
        return "http://localhost:" + BACKEND.port();
    }

    private static DisposableServer startBackend() {
        return HttpServer.create()
            .port(0)
            .handle((request, response) -> {
                String path = request.path().startsWith("/")
                    ? request.path() : "/" + request.path();
                if (path.contains("/.well-known/jwks.json")) {
                    String publicJwk = TestJwtFactory.publicJwk("test-key-1").toJSONString();
                    return response.header("Content-Type", "application/json; charset=UTF-8")
                        .sendString(Mono.just("{\"keys\":[" + publicJwk + "]}"));
                }
                if (path.startsWith("/api/unavailable")) {
                    return response.withConnection(connection -> connection.dispose()).send();
                }
                if (path.startsWith("/api/downstream-failure")) {
                    return response.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .sendString(Mono.just("{\"secret\":\"database details\"}"));
                }
                if (path.startsWith("/api/auth/login")) {
                    String authorization = request.requestHeaders().get("Authorization");
                    return response.header("Content-Type", "application/json; charset=UTF-8")
                        .sendString(Mono.just("{\"forwarded\":true,\"authorization\":\""
                            + authorization + "\"}"));
                }
                return response.header("Content-Type", "application/json; charset=UTF-8")
                    .sendString(Mono.just("{\"forwarded\":true}"));
            })
            .bindNow();
    }
}
