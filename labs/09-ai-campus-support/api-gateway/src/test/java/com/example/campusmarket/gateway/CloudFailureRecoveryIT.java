package com.example.campusmarket.gateway;

import com.example.campusmarket.gateway.support.CloudApplicationCluster;
import com.example.campusmarket.testsupport.HttpAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过四个真实应用和 MySQL/Redis 夹具验收身份、发现和目标服务故障语义。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFailureRecoveryIT {
    private static final String PASSWORD = "correct horse battery staple";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EUREKA_CACHE_WINDOW = Duration.ofSeconds(3);
    private static final Duration RECOVERY_WINDOW = Duration.ofSeconds(30);
    private static final String DEPENDENCY_CODE = "DEPENDENCY_UNAVAILABLE";
    private static final String DEPENDENCY_MESSAGE = "依赖服务暂时不可用";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudApplicationCluster cluster;
    private Account account;

    @BeforeAll
    void startCluster() {
        cluster = CloudApplicationCluster.start();
    }

    @AfterAll
    void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    @Order(0)
    void identityAndDiscoveryHealthProbesAreAvailableWithoutBearerToken() throws Exception {
        for (URI base : new URI[]{cluster.identityBaseUri(), cluster.discoveryHttpBaseUri()}) {
            for (String path : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
                HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(response.statusCode()).as("operational probe " + base + path).isEqualTo(200);
                assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo("UP");
            }
        }
    }

    @Test
    @Order(1)
    void cachedTokenStillReachesLegacyWhileIdentityIsDown() throws Exception {
        account = registerAndLoginThroughGateway();
        assertThat(createDraft(account.token()).statusCode()).isEqualTo(201);
        assertReadinessUp("all dependencies are initially ready");

        cluster.stopIdentity();
        try {
            assertIdentityOutageReadiness();

            long started = System.nanoTime();
            HttpResponse<String> newLogin = post("/api/auth/login", loginJson(account.email()));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("identity outage must be bounded by the Gateway client timeout")
                .isLessThan(Duration.ofSeconds(5));
            assertSafeUnavailable(newLogin, "identity service");

            HttpResponse<String> cachedTokenRequest = createDraft(account.token());
            assertThat(cachedTokenRequest.statusCode())
                .as("a token verified before identity outage remains usable")
                .isEqualTo(201);
            HttpAssertions.assertJsonUtf8(cachedTokenRequest);
        } finally {
            cluster.restartIdentity();
        }
    }

    @Test
    @Order(2)
    void legacyOutageIsSafe503AndRecoversWithinThirtySeconds() throws Exception {
        if (account == null) {
            account = registerAndLoginThroughGateway();
        }

        cluster.stopLegacy();
        boolean legacyRestarted = false;
        try {
            HttpResponse<String> unavailable = createDraft(account.token());
            assertSafeUnavailable(unavailable, "legacy market service");

            cluster.restartLegacy();
            legacyRestarted = true;
            Instant deadline = Instant.now().plus(RECOVERY_WINDOW);
            HttpResponse<String> recovered = awaitCreatedDraft(account.token(), deadline);
            assertThat(recovered.statusCode()).isEqualTo(201);
            HttpAssertions.assertJsonUtf8(recovered);
        } finally {
            if (!legacyRestarted) {
                cluster.restartLegacy();
            }
        }
    }

    @Test
    @Order(3)
    void cachedEurekaRegistrationRemainsUsableDuringDiscoveryOutage() throws Exception {
        if (account == null) {
            account = registerAndLoginThroughGateway();
        }

        assertReadinessUp("Eureka registration is primed before the outage");
        cluster.stopDiscovery();
        try {
            assertReadinessUp("a primed Eureka probe stays ready during a short discovery outage");
            Instant deadline = Instant.now().plus(EUREKA_CACHE_WINDOW);
            HttpResponse<String> response = awaitCreatedDraft(account.token(), deadline);

            assertThat(response.statusCode())
                .as("Gateway must use the cached Eureka registration for the bounded outage window")
                .isEqualTo(201);
            HttpAssertions.assertJsonUtf8(response);
        } finally {
            // Discovery is deliberately the last failure in this journey; close() owns cleanup.
        }
    }

    private Account registerAndLoginThroughGateway() throws Exception {
        String email = "故障" + UUID.randomUUID() + "@stu.example.edu.cn";
        HttpResponse<String> verification = post("/api/auth/email-verifications",
            "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}");
        assertThat(verification.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(verification);

        String code = cluster.latestVerificationCode(email);
        assertThat(code).matches("\\d{6}");
        HttpResponse<String> registered = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                + "\",\"code\":\"" + code + "\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        HttpAssertions.assertJsonUtf8(registered);

        HttpResponse<String> login = post("/api/auth/login", loginJson(email));
        assertThat(login.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(login);
        JsonNode body = mapper.readTree(login.body());
        String token = body.path("accessToken").asText();
        assertThat(token).isNotBlank();
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asInt()).isEqualTo(900);
        return new Account(email, token);
    }

    private HttpResponse<String> awaitCreatedDraft(String token, Instant deadline) throws Exception {
        HttpResponse<String> last = null;
        while (true) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                break;
            }
            try {
                last = createDraft(token, min(REQUEST_TIMEOUT, remaining));
                if (last.statusCode() == 201) {
                    return last;
                }
            } catch (java.io.IOException interruptedRequest) {
                // 目标服务可能仍在注册；继续轮询直到固定恢复窗口结束。
                last = null;
            }
            Duration afterRequest = Duration.between(Instant.now(), deadline);
            if (afterRequest.isZero() || afterRequest.isNegative()) {
                break;
            }
            Thread.sleep(Math.min(100, afterRequest.toMillis()));
        }
        if (last == null) {
            throw new AssertionError("恢复窗口内请求始终无法建立");
        }
        return last;
    }

    private HttpResponse<String> createDraft(String token) throws Exception {
        return createDraft(token, REQUEST_TIMEOUT);
    }

    private HttpResponse<String> createDraft(String token, Duration timeout) throws Exception {
        String body = "{\"title\":\"故障恢复草稿\",\"description\":\"真实 MySQL 草稿\","
            + "\"category\":\"教材\",\"unitPriceFen\":5600,\"availableQuantity\":1}";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/listings"))
            .timeout(timeout)
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertReadinessUp(String reason) throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        assertThat(response.statusCode())
            .as(reason + " response=" + response.body())
            .isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("status").asText()).as(reason).isEqualTo("UP");
        assertThat(body.path("components").path("jwks").path("status").asText())
            .as(reason + ": JWKS").isEqualTo("UP");
        assertThat(body.path("components").path("eureka").path("status").asText())
            .as(reason + ": Eureka").isEqualTo("UP");
    }

    private void assertIdentityOutageReadiness() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        assertThat(response.statusCode()).as("identity instance has left Eureka").isEqualTo(503);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("components").path("jwks").path("status").asText())
            .as("verified public key remains in the bounded outage window").isEqualTo("UP");
        assertThat(body.path("components").path("eureka").path("status").asText())
            .as("identity route is unavailable after instance deregistration").isEqualTo("DOWN");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertSafeUnavailable(HttpResponse<String> response, String dependency) throws Exception {
        assertThat(response.statusCode()).as(dependency).isEqualTo(503);
        HttpAssertions.assertJsonUtf8(response);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(DEPENDENCY_CODE);
        assertThat(body.path("message").asText()).isEqualTo(DEPENDENCY_MESSAGE);
        assertThat(response.body())
            .doesNotContain("localhost", "127.0.0.1", "identity-service", "legacy-market-service",
                "IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE", "java.lang", "Exception", " at ")
            .doesNotMatch(".*:[0-9]{2,5}.*");
    }

    private URI uri(String path) {
        return cluster.gatewayBaseUri().resolve(path);
    }

    private static String loginJson(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private record Account(String email, String token) {
    }
}
