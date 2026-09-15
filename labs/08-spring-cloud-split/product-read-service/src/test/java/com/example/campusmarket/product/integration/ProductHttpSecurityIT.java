package com.example.campusmarket.product.integration;

import com.example.campusmarket.product.api.SearchController;
import com.example.campusmarket.product.search.ProductSearchPort;
import com.example.campusmarket.product.security.ProductResourceServerConfiguration;
import com.example.campusmarket.testsupport.HttpAssertions;
import com.example.campusmarket.testsupport.TestJwtFactory;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 直接访问商品读服务也必须独立验 RS256/JWKS 与严格身份声明。 */
@SpringBootTest(classes = ProductHttpSecurityIT.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "eureka.client.enabled=false")
class ProductHttpSecurityIT {
    private static final String ISSUER = "http://gateway.test";
    private static final String AUDIENCE = "campus-market-api";
    private static final String KID = "test-key-1";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final HttpServer JWKS_SERVER = jwksServer();

    @LocalServerPort private int port;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("campus.market.jwt.audience", () -> AUDIENCE);
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void directProductPortRejectsForgedIdentityHeadersWithoutBearer() throws Exception {
        HttpResponse<String> response = get(null, Map.of(
            "X-User-Id", USER_ID.toString(), "X-User-Roles", "ROLE_ADMIN"));

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    void validKnownKidRs256UserTokenCanSearch() throws Exception {
        HttpResponse<String> response = get(validToken(), Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(response);
        assertThat(response.body()).contains("\"items\":[]", "\"total\":0");
    }

    @Test
    void wrongIssuerAudienceUnknownKidAndExpiredOrLongLifetimeAlwaysReturn401() throws Exception {
        Instant now = Instant.now();
        for (String invalid : List.of(
            TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), now, TestJwtFactory.ACCESS_TTL,
                "http://other.test", AUDIENCE, KID),
            TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), now, TestJwtFactory.ACCESS_TTL,
                ISSUER, "other-api", KID),
            TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), now, TestJwtFactory.ACCESS_TTL,
                ISSUER, AUDIENCE, "unknown-kid"),
            TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), now.minus(Duration.ofHours(1)),
                TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, KID),
            rsToken(new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
                .audience(AUDIENCE).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("roles", List.of("ROLE_USER")).build()))) {
            HttpResponse<String> response = get(invalid, Map.of());
            assertThat(response.statusCode()).isEqualTo(401);
            HttpAssertions.assertJsonUtf8(response);
        }
    }

    @Test
    void missingIatMalformedSubOrRolesAndHs256AlwaysReturn401() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder base = new JWTClaimsSet.Builder().subject(USER_ID.toString())
            .issuer(ISSUER).audience(AUDIENCE).issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(TestJwtFactory.ACCESS_TTL)))
            .claim("roles", List.of("ROLE_USER"));
        for (String invalid : List.of(
            rsToken(new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
                .audience(AUDIENCE).expirationTime(Date.from(now.plus(TestJwtFactory.ACCESS_TTL)))
                .claim("roles", List.of("ROLE_USER")).build()),
            rsToken(base.subject("not-a-uuid").build()),
            rsToken(base.subject(USER_ID.toString()).claim("roles", "ROLE_USER").build()),
            hsToken(base.claim("roles", List.of("ROLE_USER")).build()))) {
            HttpResponse<String> response = get(invalid, Map.of());
            assertThat(response.statusCode()).isEqualTo(401);
            HttpAssertions.assertJsonUtf8(response);
        }
    }

    private HttpResponse<String> get(String token, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/search?keyword=%E6%95%99%E6%9D%90")).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String validToken() {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now(),
            TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, KID);
    }

    private static String rsToken(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
            jwt.sign(new RSASSASigner(TestRsaKeys.privateKey()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hsToken(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), claims);
            jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static HttpServer jwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String body = new ObjectMapper().writeValueAsString(Map.of(
                "keys", List.of(TestJwtFactory.publicJwk(KID).toJSONObject())));
            server.createContext("/jwks", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException("无法启动商品 JWKS 测试桩", failure);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({ProductResourceServerConfiguration.class, SearchController.class})
    static class TestApplication {
        @Bean
        ProductSearchPort search() {
            return new ProductSearchPort() {
                public void index(ProductDocument document) { }
                public void tombstone(String listingId, long aggregateVersion) { }
                public void refresh() { }
                public SearchPage search(SearchRequest request) {
                    return new SearchPage(List.of(), 0, null);
                }
            };
        }
    }
}
