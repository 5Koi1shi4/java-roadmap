package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.testsupport.HttpAssertions;
import com.example.campusmarket.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 身份签发已迁出；兼容服务只验证来自身份服务的 Bearer Token。 */
@SpringBootTest(classes = LegacyMarketApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AuthFlowIT extends AuthFlowContainers {
    private static final String ISSUER = ResourceServerTestSupport.ISSUER;
    private static final String AUDIENCE = ResourceServerTestSupport.AUDIENCE;
    private static final String KID = ResourceServerTestSupport.KID;
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void forgedInternalHeadersDoNotAuthenticateDirectCalls() throws Exception {
        HttpResponse<String> response = get("/api/listings/mine", Map.of(
            "X-User-Id", USER_ID.toString(), "X-User-Roles", "ROLE_ADMIN"));

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    void ordinaryUserCannotAccessAdminRoute() throws Exception {
        HttpResponse<String> response = get("/api/admin/probe", Map.of(
            "Authorization", "Bearer " + token(Instant.now())));

        assertThat(response.statusCode()).isEqualTo(403);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    void identityIssuanceEndpointsAreNotServedByMarketService() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/listings/mine", Map.of(
            "Authorization", "Bearer " + token(Instant.now().minus(Duration.ofHours(1)))));

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = token(Instant.now());
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);

        HttpResponse<String> response = get("/api/listings/mine", Map.of(
            "Authorization", "Bearer " + tampered));

        assertThat(response.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(response);
    }

    private String token(Instant issuedAt) {
        return TestJwtFactory.issue(USER_ID, Set.of("ROLE_USER"), issuedAt,
            TestJwtFactory.ACCESS_TTL, ISSUER, AUDIENCE, KID);
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + path)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
