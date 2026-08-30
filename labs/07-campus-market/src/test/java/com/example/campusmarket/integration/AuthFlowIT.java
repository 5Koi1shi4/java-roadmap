package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import com.example.campusmarket.identity.infrastructure.JwtService;
import com.example.campusmarket.identity.infrastructure.LocalVerificationMailSender;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.time.Duration;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AuthFlowIT extends SharedContainers {
    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private LocalVerificationMailSender mailSender;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    @Test
    void completesRegistrationAndLoginWithOneTimeCode() throws Exception {
        String email = "学生" + UUID.randomUUID() + "@stu.example.edu.cn";
        HttpResponse<String> codeResponse = post("/api/auth/email-verifications",
            "{\"email\":\"" + email + "\",\"client\":\"auth-flow\"}");

        assertThat(codeResponse.statusCode()).isEqualTo(200);
        assertJsonUtf8(codeResponse);
        String code = mailSender.latestCode(email);
        assertThat(code).isNotBlank();

        HttpResponse<String> registered = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\",\"code\":\"" + code + "\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        assertJsonUtf8(registered);

        HttpResponse<String> reused = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\",\"code\":\"" + code + "\"}");
        assertThat(reused.statusCode()).isEqualTo(401);

        HttpResponse<String> login = post("/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertJsonUtf8(login);
        String token = jsonField(login.body(), "accessToken");
        var claims = jwtService.parse(token);
        assertThat(claims.getExpiration().toInstant())
            .isEqualTo(claims.getIssuedAt().toInstant().plus(Duration.ofMinutes(15)));

        HttpResponse<String> protectedResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedResponse.statusCode()).isEqualTo(404);

        HttpResponse<String> invalidSignature = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + token.substring(0, token.length() - 1) + "x")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalidSignature.statusCode()).isEqualTo(401);
        assertJsonUtf8(invalidSignature);

        HttpResponse<String> forbidden = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/probe"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertJsonUtf8(forbidden);

        JwtService expiredService = new JwtService(
            "local-only-jwt-secret-change-me-32-bytes", Duration.ofMinutes(15),
            Clock.offset(Clock.systemUTC(), Duration.ofHours(-1)));
        String expired = expiredService.issue(new com.example.campusmarket.identity.application.AuthenticatedUser(
            UUID.randomUUID(), java.util.Set.of("ROLE_USER")));
        HttpResponse<String> expiredResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + expired).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(expiredResponse.statusCode()).isEqualTo(401);
        assertJsonUtf8(expiredResponse);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertJsonUtf8(HttpResponse<String> response) {
        MediaType contentType = MediaType.parseMediaType(response.headers()
            .firstValue("Content-Type").orElseThrow());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("json");
        assertThat(contentType.getCharset()).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String jsonField(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        assertThat(matcher.find()).as("response contains %s", field).isTrue();
        return matcher.group(1);
    }
}
