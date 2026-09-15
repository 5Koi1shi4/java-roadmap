package com.example.campusmarket.gateway;

import com.example.campusmarket.gateway.support.CloudApplicationCluster;
import com.example.campusmarket.testsupport.HttpAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过 Gateway 验收五应用注册、商品搜索路由和交易事实边界。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductReadJourneyIT {
    private static final String PASSWORD = "correct horse battery staple";
    private static final String TITLE = "独立读侧 Java 并发教材";
    private static final String DESCRIPTION = "真实五应用旅程商品描述";
    private static final String CATEGORY = "教材";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEARCH_RECOVERY_WINDOW = Duration.ofSeconds(45);

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
    void routesPublishedListingToReadServiceAndKeepsOrderSnapshotWhenSoldOut() throws Exception {
        assertThat(cluster.registryApplications())
            .containsExactlyInAnyOrder("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE",
                "PRODUCT-READ-SERVICE", "API-GATEWAY");
        assertThat(cluster.gatewayRouteUris())
            .containsEntry("product-search", "lb://product-read-service")
            .containsEntry("product-listing-search", "lb://product-read-service");
        assertRegisteredPorts();

        HttpResponse<String> directWithoutBearer = http.send(HttpRequest.newBuilder(
                cluster.productReadBaseUri().resolve("/api/search?keyword=教材"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(directWithoutBearer.statusCode()).isEqualTo(401);
        HttpAssertions.assertJsonUtf8(directWithoutBearer);

        Account seller = registerAndLogin("商品卖家");
        Account buyer = registerAndLogin("商品买家");
        HttpResponse<String> created = postBearer("/api/listings", seller.token(),
            "{\"title\":\"" + TITLE + "\",\"description\":\"" + DESCRIPTION
                + "\",\"category\":\"" + CATEGORY
                + "\",\"unitPriceFen\":5600,\"availableQuantity\":1}", Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        HttpAssertions.assertJsonUtf8(created);
        UUID listingId = UUID.fromString(mapper.readTree(created.body()).path("id").asText());

        assertThat(uploadMedia(listingId, seller.token()).statusCode()).isEqualTo(201);
        HttpResponse<String> published = postBearer("/api/listings/" + listingId + "/publish",
            seller.token(), "{}", Map.of());
        assertThat(published.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(published);

        JsonNode visible = awaitSearchContains(buyer.token(), TITLE, listingId);
        assertThat(visible.path("title").asText()).isEqualTo(TITLE);
        assertThat(visible.path("status").asText()).isEqualTo("ON_SALE");
        assertThat(visible.path("availableQuantity").asInt()).isEqualTo(1);

        HttpResponse<String> order = postBearer("/api/orders", buyer.token(),
            "{\"listingId\":\"" + listingId + "\",\"quantity\":1}",
            Map.of("Idempotency-Key", "journey-order-" + UUID.randomUUID()));
        assertThat(order.statusCode()).isEqualTo(201);
        HttpAssertions.assertJsonUtf8(order);
        UUID orderId = UUID.fromString(mapper.readTree(order.body()).path("orderId").asText());

        CloudApplicationCluster.OrderSnapshot before = cluster.orderSnapshot(orderId);
        assertThat(before.listingTitle()).isEqualTo(TITLE);
        assertThat(before.listingDescription()).isEqualTo(DESCRIPTION);
        assertThat(before.unitPriceFen()).isEqualTo(5600);
        assertThat(before.quantity()).isEqualTo(1);
        assertThat(before.totalAmountFen()).isEqualTo(5600);

        awaitSearchAbsent(buyer.token(), TITLE, listingId);
        assertThat(cluster.orderSnapshot(orderId)).isEqualTo(before);
    }

    private void assertRegisteredPorts() {
        Map<String, Integer> webPorts = cluster.webServerPorts();
        Map<String, List<Integer>> registered = cluster.registryInstancePorts();
        assertThat(webPorts).containsKeys("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE",
            "PRODUCT-READ-SERVICE", "API-GATEWAY");
        assertThat(registered).containsKeys("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE",
            "PRODUCT-READ-SERVICE", "API-GATEWAY");
        for (String name : List.of("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE",
            "PRODUCT-READ-SERVICE", "API-GATEWAY")) {
            assertThat(registered.get(name)).containsExactly(webPorts.get(name));
        }
    }

    private Account registerAndLogin(String prefix) throws Exception {
        String email = prefix + UUID.randomUUID() + "@stu.example.edu.cn";
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

        HttpResponse<String> login = post("/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        HttpAssertions.assertJsonUtf8(login);
        JsonNode body = mapper.readTree(login.body());
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asInt()).isEqualTo(900);
        return new Account(email, body.path("accessToken").asText());
    }

    private HttpResponse<String> uploadMedia(UUID listingId, String token) throws Exception {
        String boundary = "----campus-market-" + UUID.randomUUID();
        byte[] png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        java.io.ByteArrayOutputStream multipart = new java.io.ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"cover.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(png);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/listings/" + listingId + "/media"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode awaitSearchContains(String token, String keyword, UUID listingId) throws Exception {
        Instant deadline = Instant.now().plus(SEARCH_RECOVERY_WINDOW);
        HttpResponse<String> last = null;
        while (Instant.now().isBefore(deadline)) {
            last = getBearer("/api/search?keyword=" + encode(keyword) + "&size=20", token);
            if (last.statusCode() == 200) {
                JsonNode body = mapper.readTree(last.body());
                for (JsonNode item : body.path("items")) {
                    if (listingId.toString().equals(item.path("listingId").asText())) {
                        return item;
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("发布商品未在恢复窗口内收敛到读侧搜索："
            + (last == null ? "无响应" : last.body()));
    }

    private void awaitSearchAbsent(String token, String keyword, UUID listingId) throws Exception {
        Instant deadline = Instant.now().plus(SEARCH_RECOVERY_WINDOW);
        HttpResponse<String> last = null;
        while (Instant.now().isBefore(deadline)) {
            last = getBearer("/api/search?keyword=" + encode(keyword) + "&size=20", token);
            if (last.statusCode() == 200) {
                JsonNode body = mapper.readTree(last.body());
                boolean present = false;
                for (JsonNode item : body.path("items")) {
                    present |= listingId.toString().equals(item.path("listingId").asText());
                }
                if (!present) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("售罄商品仍在读侧搜索：" + (last == null ? "无响应" : last.body()));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postBearer(String path, String token, String body,
                                            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=UTF-8");
        headers.forEach(builder::header);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> getBearer(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return cluster.gatewayBaseUri().resolve(path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Account(String email, String token) {
    }
}
