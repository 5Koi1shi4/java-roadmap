package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ConcurrentOrderIT extends SharedContainers {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @MockBean private com.example.campusmarket.order.application.OrderCreationHook orderCreationHook;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void twentyBuyersCompeteForSixStockAndPersistAtomicOrderArtifacts() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 6, 1_200, 90);
        String token = token(buyer);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 20; i++) {
                String key = "order-" + UUID.randomUUID();
                requests.add(CompletableFuture.supplyAsync(() -> post(token, listing, 1, key), pool));
            }
            var responses = requests.stream().map(future -> future.join()).toList();
            assertThat(responses.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(6);
            assertThat(responses.stream().filter(response -> response.statusCode() == 409).count()).isEqualTo(14);
            assertJsonUtf8(responses.get(0));
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=? AND order_id IS NOT NULL", Integer.class, listing.toString())).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_CREATED' AND aggregate_id IN (SELECT id FROM trade_order WHERE listing_id=?)", Integer.class, listing.toString())).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT MIN(TIMESTAMPDIFF(SECOND, created_at, payment_deadline)) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(900);
            assertThat(jdbc.queryForObject("SELECT MIN(warranty_days) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(90);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyReplaysByteForByteAndDifferentBodyConflicts() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 2, 777, null);
        String token = token(buyer);
        String key = "same-key-" + UUID.randomUUID();
        HttpResponse<String> first = post(token, listing, 1, key);
        HttpResponse<String> replay = post(token, listing, 1, key);
        HttpResponse<String> conflict = post(token, listing, 2, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertJsonUtf8(replay);
        assertThat(replay.body().getBytes(StandardCharsets.UTF_8)).containsExactly(first.body().getBytes(StandardCharsets.UTF_8));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(1);
    }

    @Test
    void failedTransactionLeavesNoArtifactsAndSameKeyCanRetry() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 1, 777, null);
        String token = token(buyer);
        String key = "rollback-key-" + UUID.randomUUID();
        doThrow(new IllegalStateException("injected failure")).when(orderCreationHook).afterInventoryDeducted(org.mockito.ArgumentMatchers.any());
        try {
            assertThat(post(token, listing, 1, key).statusCode()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command WHERE actor_id=? AND idempotency_key=?", Integer.class, buyer.toString(), key)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=?", Integer.class, listing.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        } finally {
            reset(orderCreationHook);
        }
        HttpResponse<String> retry = post(token, listing, 1, key);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command WHERE actor_id=? AND idempotency_key=?", Integer.class, buyer.toString(), key)).isEqualTo(1);
    }

    private HttpResponse<String> post(String token, UUID listing, int quantity, String key) {
        try {
            String body = "{\"listingId\":\"" + listing + "\",\"quantity\":" + quantity + "}";
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }

    private UUID listing(UUID seller, int quantity, long unitPrice, Integer warrantyDays) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,warranty_days,warranty_scope,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,?,?, 'ON_SALE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), seller.toString(), "高等数学", "九成新", "教材", unitPrice, quantity, warrantyDays,
            warrantyDays == null ? null : "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE");
        return id;
    }

    private String token(UUID user) {
        return jwtService.issue(new AuthenticatedUser(user, Set.of("ROLE_USER")));
    }

    private static void assertJsonUtf8(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElseThrow();
        MediaType mediaType = MediaType.parseMediaType(contentType);
        assertThat(mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(mediaType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
    }
}
