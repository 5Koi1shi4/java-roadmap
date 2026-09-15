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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过真实 Gateway 验证 Rabbit、读服务和 Elasticsearch 停机后的安全恢复。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductReadFailureRecoveryIT {
    private static final String PASSWORD = "correct horse battery staple";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECOVERY_WINDOW = Duration.ofSeconds(45);
    private static final String DEPENDENCY_CODE = "DEPENDENCY_UNAVAILABLE";
    private static final String DEPENDENCY_MESSAGE = "依赖服务暂时不可用";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudApplicationCluster cluster;

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
    @Order(1)
    void brokerOutageDoesNotBlockPublishAndRecoversTheReadProjection() throws Exception {
        Account seller = registerAndLogin("Rabbit故障卖家");
        PublishedListing listing = createListing(seller, "Rabbit 恢复商品");
        cluster.stopRabbit();
        try {
            HttpResponse<String> published = postBearer(
                "/api/listings/" + listing.id() + "/publish", seller.token(), "{}");
            assertThat(published.statusCode())
                .as("Rabbit 停机时交易事实仍应能提交")
                .isEqualTo(200);
            HttpAssertions.assertJsonUtf8(published);
        } finally {
            cluster.restartRabbit();
        }

        awaitSearchContains(seller.token(), listing.title(), listing.id());
    }

    @Test
    @Order(2)
    void productReadOutageReturnsSafe503AndRecoversThroughGateway() throws Exception {
        Account seller = registerAndLogin("读服务故障卖家");
        PublishedListing listing = createAndAwaitVisible(seller, "读服务恢复商品");

        cluster.stopProductRead();
        try {
            HttpResponse<String> unavailable = search(seller.token(), listing.title());
            assertSafeUnavailable(unavailable);
        } finally {
            cluster.restartProductRead();
        }

        awaitSearchContains(seller.token(), listing.title(), listing.id());
    }

    @Test
    @Order(3)
    void elasticsearchOutageReturnsSafe503AndRebuildsFromReadOutbox() throws Exception {
        Account seller = registerAndLogin("搜索故障卖家");
        PublishedListing listing = createAndAwaitVisible(seller, "搜索恢复商品");

        cluster.stopElasticsearch();
        try {
            HttpResponse<String> unavailable = search(seller.token(), listing.title());
            assertSafeUnavailable(unavailable);
        } finally {
            cluster.restartElasticsearch();
        }

        awaitSearchContains(seller.token(), listing.title(), listing.id());
    }

    private PublishedListing createAndAwaitVisible(Account seller, String title) throws Exception {
        PublishedListing listing = createListing(seller, title);
        HttpResponse<String> published = postBearer(
            "/api/listings/" + listing.id() + "/publish", seller.token(), "{}");
        assertThat(published.statusCode()).isEqualTo(200);
        awaitSearchContains(seller.token(), title, listing.id());
        return listing;
    }

    private PublishedListing createListing(Account seller, String title) throws Exception {
        HttpResponse<String> created = postBearer("/api/listings", seller.token(),
            "{\"title\":\"" + title + "\",\"description\":\"故障恢复商品描述\","
                + "\"category\":\"教材\",\"unitPriceFen\":5600,\"availableQuantity\":1}");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID listingId = UUID.fromString(mapper.readTree(created.body()).path("id").asText());
        assertThat(uploadMedia(listingId, seller.token()).statusCode()).isEqualTo(201);
        return new PublishedListing(listingId, title);
    }

    private void awaitSearchContains(String token, String keyword, UUID listingId) throws Exception {
        Instant deadline = Instant.now().plus(RECOVERY_WINDOW);
        HttpResponse<String> last = null;
        while (Instant.now().isBefore(deadline)) {
            last = search(token, keyword);
            if (last.statusCode() == 200) {
                JsonNode body = mapper.readTree(last.body());
                for (JsonNode item : body.path("items")) {
                    if (listingId.toString().equals(item.path("listingId").asText())) {
                        return;
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("商品未在故障恢复窗口内回到 Gateway 搜索："
            + (last == null ? "无响应" : last.body()));
    }

    private void assertSafeUnavailable(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(503);
        HttpAssertions.assertJsonUtf8(response);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(DEPENDENCY_CODE);
        assertThat(body.path("message").asText()).isEqualTo(DEPENDENCY_MESSAGE);
        assertThat(response.body())
            .doesNotContain("localhost", "127.0.0.1", "product-read-service",
                "PRODUCT-READ-SERVICE", "java.lang", "Exception", " at ")
            .doesNotMatch(".*:[0-9]{2,5}.*");
    }

    private Account registerAndLogin(String prefix) throws Exception {
        String email = prefix + UUID.randomUUID() + "@stu.example.edu.cn";
        HttpResponse<String> verification = post("/api/auth/email-verifications",
            "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}");
        assertThat(verification.statusCode()).isEqualTo(200);
        String code = cluster.latestVerificationCode(email);
        HttpResponse<String> registered = post("/api/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                + "\",\"code\":\"" + code + "\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        HttpResponse<String> login = post("/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        return new Account(email, mapper.readTree(login.body()).path("accessToken").asText());
    }

    private HttpResponse<String> uploadMedia(UUID listingId, String token) throws Exception {
        String boundary = "----campus-market-" + UUID.randomUUID();
        String multipart = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"cover.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\n"
            + "故障恢复媒体\r\n"
            + "--" + boundary + "--\r\n";
        return http.send(HttpRequest.newBuilder(uri("/api/listings/" + listingId + "/media"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> search(String token, String keyword) throws Exception {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(uri("/api/search?keyword=" + encoded + "&size=20"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postBearer(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return cluster.gatewayBaseUri().resolve(path);
    }

    private record Account(String email, String token) {
    }

    private record PublishedListing(UUID id, String title) {
    }
}
