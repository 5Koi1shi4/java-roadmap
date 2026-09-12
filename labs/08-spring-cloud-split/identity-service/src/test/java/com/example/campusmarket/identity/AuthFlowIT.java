package com.example.campusmarket.identity;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.LocalVerificationMailSender;
import com.example.campusmarket.identity.security.IdentityTokenIssuer;
import com.example.campusmarket.identity.security.RsaKeyProperties;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 身份服务真实 MySQL+Redis HTTP 验收。 */
@SpringBootTest(classes = IdentityServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIT extends AuthFlowContainers {
    @LocalServerPort
    private int port;

    @Autowired
    private IdentityTokenIssuer tokenIssuer;

    @Autowired
    private LocalVerificationMailSender mailSender;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    @Test
    @Order(1)
    void completesRegistrationAndLoginWithOneTimeCode() throws Exception {
        String email = uniqueEmail();
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

        Map<String, Object> user = jdbc.queryForMap("SELECT password_hash FROM campus_user WHERE email = ?", email);
        assertThat(user.get("password_hash").toString()).matches("\\$2[aby]\\$[0-9]{2}\\$.+");
        Map<String, Object> verification = jdbc.queryForMap(
            "SELECT status, attempt_count, consumed_at FROM email_verification "
                + "WHERE email = ? AND purpose = 'REGISTER' ORDER BY created_at DESC LIMIT 1", email);
        assertThat(verification.get("status")).isEqualTo("VERIFIED");
        assertThat(((Number) verification.get("attempt_count")).intValue()).isZero();
        assertThat(verification.get("consumed_at")).isNotNull();

        HttpResponse<String> reused = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\",\"code\":\"" + code + "\"}");
        assertThat(reused.statusCode()).isEqualTo(401);

        HttpResponse<String> login = post("/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertJsonUtf8(login);
        String token = jsonField(login.body(), "accessToken");
        var claims = tokenIssuer.parse(token);
        assertThat(claims.getIssuer()).isEqualTo("http://gateway.test");
        assertThat(claims.getAudience()).containsExactly("campus-market-api");
        assertThat(claims.getExpirationTime().toInstant())
            .isEqualTo(claims.getIssueTime().toInstant().plus(Duration.ofMinutes(15)));

        HttpResponse<String> protectedResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedResponse.statusCode()).isEqualTo(404);

        int signatureStart = token.lastIndexOf('.') + 1;
        String signature = token.substring(signatureStart);
        char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
        String forgedToken = token.substring(0, signatureStart) + replacement + signature.substring(1);
        HttpResponse<String> invalidSignature = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + forgedToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalidSignature.statusCode()).isEqualTo(401);
        assertJsonUtf8(invalidSignature);

        HttpResponse<String> forbidden = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/probe"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertJsonUtf8(forbidden);
        assertThat(forbidden.body()).contains("无权");

        HttpResponse<String> forgedDevice = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/email-verifications"))
                .header("Content-Type", "application/json")
                .header("Cookie", "campus_device=attacker-controlled")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"另一个" + UUID.randomUUID() + "@stu.example.edu.cn\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forgedDevice.statusCode()).isEqualTo(200);
        assertThat(forgedDevice.headers().allValues("Set-Cookie")).anyMatch(value ->
            value.startsWith("campus_device=") && value.contains(".") && value.contains("HttpOnly")
                && value.contains("SameSite=Strict"));

        IdentityTokenIssuer expiredIssuer = new IdentityTokenIssuer(new RsaKeyProperties(
            "http://gateway.test", "campus-market-api", "identity-key-1",
            new FileSystemResource(keyDirectory().resolve("test-private-key.pem")),
            new FileSystemResource(keyDirectory().resolve("test-public-key.pem"))),
            Clock.offset(Clock.systemUTC(), Duration.ofHours(-1)));
        String expired = expiredIssuer.issue(new AuthenticatedUser(UUID.randomUUID(), java.util.Set.of("ROLE_USER")));
        HttpResponse<String> expiredResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private"))
                .header("Authorization", "Bearer " + expired).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(expiredResponse.statusCode()).isEqualTo(401);
        assertJsonUtf8(expiredResponse);

        HttpResponse<String> chineseError = post("/api/auth/register",
            "{\"email\":\"用户" + UUID.randomUUID() + "@not-campus.example.com\","
                + "\"password\":\"correct horse battery staple\",\"code\":\"000000\"}");
        assertThat(chineseError.statusCode()).isEqualTo(400);
        assertJsonUtf8(chineseError);
        assertThat(chineseError.body()).contains("请求参数");
    }

    @Test
    @Order(2)
    void enforcesEmailSendRateLimit() throws Exception {
        flushRedis();
        String email = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}")
                .statusCode()).isEqualTo(200);
        }
        HttpResponse<String> fourth = post("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}");
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertJsonUtf8(fourth);
    }

    @Test
    @Order(3)
    void enforcesRemoteIpSendRateLimit() throws Exception {
        flushRedis();
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> response = post(HttpClient.newHttpClient(), "/api/auth/email-verifications",
                "{\"email\":\"" + uniqueEmail() + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
        }
        HttpResponse<String> twentyFirst = post(HttpClient.newHttpClient(), "/api/auth/email-verifications",
            "{\"email\":\"" + uniqueEmail() + "\"}");
        assertThat(twentyFirst.statusCode()).isEqualTo(429);
        assertJsonUtf8(twentyFirst);
    }

    @Test
    @Order(4)
    void enforcesSignedDeviceSendRateLimit() throws Exception {
        flushRedis();
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = post("/api/auth/email-verifications",
                "{\"email\":\"" + uniqueEmail() + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
        }
        HttpResponse<String> eleventh = post("/api/auth/email-verifications",
            "{\"email\":\"" + uniqueEmail() + "\"}");
        assertThat(eleventh.statusCode()).isEqualTo(429);
        assertJsonUtf8(eleventh);
    }

    @Test
    @Order(5)
    void locksVerificationAfterFiveFailedAttempts() throws Exception {
        flushRedis();
        String email = uniqueEmail();
        assertThat(post("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}")
            .statusCode()).isEqualTo(200);
        String actualCode = mailSender.latestCode(email);
        String wrongCode = "000000".equals(actualCode) ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = post("/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\","
                    + "\"code\":\"" + wrongCode + "\"}");
            assertThat(response.statusCode()).isEqualTo(401);
        }
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, attempt_count FROM email_verification WHERE email = ? "
                + "AND purpose = 'REGISTER' ORDER BY created_at DESC LIMIT 1", email);
        assertThat(row.get("status")).isEqualTo("LOCKED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(5);

        HttpResponse<String> afterLock = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\","
                + "\"code\":\"" + actualCode + "\"}");
        assertThat(afterLock.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(99)
    void returns503WhenRedisIsUnavailable() throws Exception {
        flushRedis();
        REDIS.stop();
        HttpResponse<String> response = post("/api/auth/email-verifications",
            "{\"email\":\"" + uniqueEmail() + "\"}");
        assertThat(response.statusCode()).isEqualTo(503);
        assertJsonUtf8(response);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return post(httpClient, path, json);
    }

    private HttpResponse<String> post(HttpClient client, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertJsonUtf8(HttpResponse<String> response) {
        MediaType contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("json");
        assertThat(contentType.getCharset()).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String jsonField(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        assertThat(matcher.find()).as("response contains %s", field).isTrue();
        return matcher.group(1);
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static String uniqueEmail() {
        return "用户" + UUID.randomUUID() + "@stu.example.edu.cn";
    }
}
