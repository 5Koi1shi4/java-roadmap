package com.example.campusmarket.security;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.testsupport.HttpAssertions;
import com.example.campusmarket.testsupport.TestJwtFactory;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 资源服务器必须只信任身份服务 JWKS，不接受内部身份 Header 或对称签名。 */
@SpringBootTest(classes = LegacyMarketApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResourceServerSecurityIT {
    private static final String ISSUER = "http://gateway.test";
    private static final String AUDIENCE = "campus-market-api";
    private static final String KID = "test-key-1";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer JWKS_SERVER = jwksServer();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void resourceServerProperties(DynamicPropertyRegistry registry) {
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
    @Order(1)
    void directCallRejectsForgedInternalHeadersWithoutBearerToken() throws Exception {
        HttpResponse<String> response = get("/api/listings/mine", Map.of(
            "X-User-Id", USER_ID.toString(), "X-User-Roles", "ROLE_ADMIN"));

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    @Order(2)
    void validUserTokenCannotAccessAdminRoute() throws Exception {
        HttpResponse<String> response = get("/api/admin/probe", Map.of(
            "Authorization", "Bearer " + userToken()));

        assertThat(response.statusCode()).isEqualTo(403);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    void missingOrBlankKidAlwaysReturns401() throws Exception {
        for (String token : List.of(missingKidToken(), blankKidToken())) {
            HttpResponse<String> response = get("/api/listings/mine", Map.of(
                "Authorization", "Bearer " + token));
            assertThat(response.statusCode()).as("kid is required").isEqualTo(401);
            HttpAssertions.assertJsonUtf8(response);
        }
    }

    @Test
    void malformedRolesAlwaysReturn401() throws Exception {
        for (String token : List.of(scalarRoleToken(), nullRoleToken(), mixedRoleToken())) {
            HttpResponse<String> response = get("/api/listings/mine", Map.of(
                "Authorization", "Bearer " + token));
            assertThat(response.statusCode()).as("roles must be a string list").isEqualTo(401);
            HttpAssertions.assertJsonUtf8(response);
        }
    }

    @Test
    @Order(3)
    void invalidBearerTokensAlwaysReturn401() throws Exception {
        for (String token : Stream.of(
            expiredToken(), wrongIssuerToken(), wrongAudienceToken(), illegalRoleToken(),
            missingIssuedAtToken(), missingExpirationToken(), futureIssuedAtToken(),
            longLifetimeToken(), unknownKidToken(), missingKidToken(), blankKidToken(),
            scalarRoleToken(), nullRoleToken(), mixedRoleToken(), hs256Token(), tamperedToken()).toList()) {
            HttpResponse<String> response = get("/api/listings/mine", Map.of(
                "Authorization", "Bearer " + token));
            assertThat(response.statusCode()).as("invalid token must be rejected: " + token).isEqualTo(401);
            HttpAssertions.assertJsonUtf8(response);
        }
    }

    private static String userToken() {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now(),
            TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, KID);
    }

    private static String expiredToken() {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now().minus(Duration.ofHours(1)),
            TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, KID);
    }

    private static String wrongIssuerToken() {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now(),
            TestJwtFactory.ACCESS_TTL, "http://evil.test", AUDIENCE, KID);
    }

    private static String wrongAudienceToken() {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now(),
            TestJwtFactory.ACCESS_TTL, ISSUER, "not-campus-market-api", KID);
    }

    private static String illegalRoleToken() {
        return signedToken(Instant.now(), Instant.now().plus(TestJwtFactory.ACCESS_TTL),
            ISSUER, AUDIENCE, KID, List.of("ROLE_ROOT"), JWSAlgorithm.RS256);
    }

    private static String missingIssuedAtToken() {
        Instant now = Instant.now();
        return signedClaims(new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
            .audience(AUDIENCE).expirationTime(Date.from(now.plus(TestJwtFactory.ACCESS_TTL)))
            .claim("roles", List.of("ROLE_USER")).build(), KID, JWSAlgorithm.RS256);
    }

    private static String missingExpirationToken() {
        return signedClaims(new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
            .audience(AUDIENCE).issueTime(Date.from(Instant.now()))
            .claim("roles", List.of("ROLE_USER")).build(), KID, JWSAlgorithm.RS256);
    }

    private static String futureIssuedAtToken() {
        Instant issuedAt = Instant.now().plus(Duration.ofHours(1));
        return signedClaims(new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
            .audience(AUDIENCE).issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(TestJwtFactory.ACCESS_TTL)))
            .claim("roles", List.of("ROLE_USER")).build(), KID, JWSAlgorithm.RS256);
    }

    private static String longLifetimeToken() {
        return signedToken(Instant.now(), Instant.now().plus(Duration.ofHours(1)),
            ISSUER, AUDIENCE, KID, List.of("ROLE_USER"), JWSAlgorithm.RS256);
    }

    private static String unknownKidToken() {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), Instant.now(),
            TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, "unknown-key");
    }

    private static String missingKidToken() {
        return signedClaims(claims(Instant.now(), Instant.now().plus(TestJwtFactory.ACCESS_TTL),
            ISSUER, AUDIENCE, List.of("ROLE_USER")), null, JWSAlgorithm.RS256);
    }

    private static String blankKidToken() {
        return signedClaims(claims(Instant.now(), Instant.now().plus(TestJwtFactory.ACCESS_TTL),
            ISSUER, AUDIENCE, List.of("ROLE_USER")), " ", JWSAlgorithm.RS256);
    }

    private static String scalarRoleToken() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
            .audience(AUDIENCE).issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(TestJwtFactory.ACCESS_TTL)))
            .claim("roles", "ROLE_USER").build();
        return signedClaims(claims, KID, JWSAlgorithm.RS256);
    }

    private static String nullRoleToken() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
            .audience(AUDIENCE).issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(TestJwtFactory.ACCESS_TTL)))
            .claim("roles", java.util.Arrays.asList("ROLE_USER", null)).build();
        return signedClaims(claims, KID, JWSAlgorithm.RS256);
    }

    private static String mixedRoleToken() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(ISSUER)
            .audience(AUDIENCE).issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(TestJwtFactory.ACCESS_TTL)))
            .claim("roles", java.util.Arrays.asList("ROLE_USER", 42)).build();
        return signedClaims(claims, KID, JWSAlgorithm.RS256);
    }

    private static String hs256Token() {
        try {
            JWTClaimsSet claims = claims(Instant.now(), Instant.now().plus(TestJwtFactory.ACCESS_TTL),
                ISSUER, AUDIENCE, List.of("ROLE_USER"));
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), claims);
            jwt.sign(new MACSigner("test-hs256-secret-that-is-at-least-256-bits-long".getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String tamperedToken() {
        String token = userToken();
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }

    private static String signedToken(Instant issuedAt, Instant expiresAt, String issuer,
                                      String audience, String kid, List<String> roles,
                                      JWSAlgorithm algorithm) {
        try {
            JWTClaimsSet claims = claims(issuedAt, expiresAt, issuer, audience, roles);
            return signedClaims(claims, kid, algorithm);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String signedClaims(JWTClaimsSet claims, String kid, JWSAlgorithm algorithm) {
        try {
            JWSHeader.Builder header = new JWSHeader.Builder(algorithm);
            if (kid != null) {
                header.keyID(kid);
            }
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            jwt.sign(new RSASSASigner(TestRsaKeys.privateKey()));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JWTClaimsSet claims(Instant issuedAt, Instant expiresAt, String issuer,
                                       String audience, List<String> roles) {
        return new JWTClaimsSet.Builder().subject(USER_ID.toString()).issuer(issuer).audience(audience)
            .claim("roles", roles).issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt)).build();
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" +
            port + path)).GET();
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer jwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RSAKey publicJwk = TestJwtFactory.publicJwk(KID);
            String body = JSON.writeValueAsString(Map.of("keys", List.of(publicJwk.toJSONObject())));
            server.createContext("/jwks", exchange -> {
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
            throw new IllegalStateException("无法启动 JWKS 测试桩", ex);
        }
    }
}
