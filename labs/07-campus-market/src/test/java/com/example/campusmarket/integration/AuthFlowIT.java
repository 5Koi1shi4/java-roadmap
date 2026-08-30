package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import com.example.campusmarket.identity.infrastructure.JwtService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    @Test
    void completesRegistrationAndLoginWithOneTimeCode() throws Exception {
        String email = "学生" + UUID.randomUUID() + "@stu.example.edu.cn";
        HttpResponse<String> codeResponse = post("/api/auth/email-verifications",
            "{\"email\":\"" + email + "\",\"client\":\"auth-flow\"}");

        assertThat(codeResponse.statusCode()).isEqualTo(200);
        assertThat(codeResponse.headers().firstValue("Content-Type").orElseThrow())
            .isEqualTo("application/json;charset=UTF-8");
        String code = jsonField(codeResponse.body(), "code");

        HttpResponse<String> registered = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\",\"code\":\"" + code + "\"}");
        assertThat(registered.statusCode()).isEqualTo(201);

        HttpResponse<String> reused = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\",\"code\":\"" + code + "\"}");
        assertThat(reused.statusCode()).isEqualTo(401);

        HttpResponse<String> login = post("/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        String token = jsonField(login.body(), "accessToken");
        var claims = jwtService.parse(token);
        assertThat(claims.getExpiration().toInstant())
            .isEqualTo(claims.getIssuedAt().toInstant().plus(Duration.ofMinutes(15)));

        HttpResponse<String> protectedResponse = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedResponse.statusCode()).isEqualTo(404);

        HttpResponse<String> invalidSignature = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + token.substring(0, token.length() - 1) + "x")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalidSignature.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonField(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        assertThat(matcher.find()).as("response contains %s", field).isTrue();
        return matcher.group(1);
    }
}
