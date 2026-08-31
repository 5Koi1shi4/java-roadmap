package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ConcurrentOrderIT extends SharedContainers {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
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
            var responses = requests.stream().map(ConcurrentOrderIT::awaitFuture).toList();
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
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sameKeyReplaysByteForByteAndDifferentBodyConflicts() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 2, 777, null);
        String token = token(buyer);
        String key = "same-key-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger lockedCalls = new AtomicInteger();
        CountDownLatch secondLockAttempt = new CountDownLatch(1);
        AtomicInteger lockAttemptCalls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (lockAttemptCalls.incrementAndGet() == 2) secondLockAttempt.countDown();
            return null;
        }).when(orderCreationHook).beforeCommandLockAttempt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(invocation -> {
            if (lockedCalls.incrementAndGet() == 1) {
                firstLocked.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS);
            }
            return null;
        }).when(orderCreationHook).afterCommandLocked(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            CompletableFuture<HttpResponse<String>> firstRequest = CompletableFuture.supplyAsync(() -> post(token, listing, 1, key), pool);
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<HttpResponse<String>> secondRequest = CompletableFuture.supplyAsync(() -> post(token, listing, 1, key), pool);
            assertThat(secondLockAttempt.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l
                      ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='order_command'
                      AND l.INDEX_NAME='uk_order_command_actor_key' AND l.LOCK_STATUS='WAITING'
                    """, Long.class)).isGreaterThan(0L));
            assertThat(secondRequest.isDone()).as("第二请求释放首锁前仍应等待 command 行锁").isFalse();
            releaseFirst.countDown();
            CompletableFuture.allOf(firstRequest, secondRequest).get(15, TimeUnit.SECONDS);
            HttpResponse<String> first = firstRequest.get(5, TimeUnit.SECONDS);
            HttpResponse<String> replay = secondRequest.get(5, TimeUnit.SECONDS);
            HttpResponse<String> conflict = post(token, listing, 2, key);
            assertThat(java.util.List.of(first, replay)).allMatch(response -> response.statusCode() == 201);
            assertJsonUtf8(first);
            assertJsonUtf8(replay);
            assertThat(replay.body().getBytes(StandardCharsets.UTF_8)).containsExactly(first.body().getBytes(StandardCharsets.UTF_8));
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command WHERE actor_id=? AND idempotency_key=?", Integer.class, buyer.toString(), key)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_CREATED' AND aggregate_id IN (SELECT id FROM trade_order WHERE listing_id=?)", Integer.class, listing.toString())).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsSelfPurchaseWithUnprocessableEntity() {
        UUID seller = user();
        UUID listing = listing(seller, 1, 777, null);

        HttpResponse<String> response = post(token(seller), listing, 1, "self-buy-" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isZero();
    }

    @Test
    void orderSnapshotAndOutboxRemainStableAfterListingChanges() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        Instant manufacturerExpiry = Instant.parse("2027-01-01T00:00:00Z");
        UUID listing = listing(seller, 1, 1_200, 90, "proof-v1", manufacturerExpiry);
        HttpResponse<String> response = post(token(buyer), listing, 1, "snapshot-" + UUID.randomUUID());
        UUID orderId = UUID.fromString(objectMapper.readTree(response.body()).get("orderId").asText());

        jdbc.update("UPDATE listing SET title=?,description=?,category=?,unit_price_fen=?,warranty_days=?,warranty_scope=?,manufacturer_warranty_proof_snapshot=?,manufacturer_warranty_expires_at=? WHERE id=?",
            "改标题", "改描述", "改分类", 9_999, null, null, "proof-v2", Timestamp.from(Instant.parse("2028-01-01T00:00:00Z")), listing.toString());

        var snapshot = jdbc.queryForMap("SELECT listing_title_snapshot,listing_description_snapshot,unit_price_fen,warranty_days,warranty_scope_snapshot,manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at FROM trade_order WHERE id=?", orderId.toString());
        assertThat(snapshot.get("listing_title_snapshot")).isEqualTo("高等数学");
        assertThat(snapshot.get("listing_description_snapshot")).isEqualTo("九成新");
        assertThat(snapshot.get("unit_price_fen")).isEqualTo(1_200L);
        assertThat(snapshot.get("warranty_days")).isEqualTo(90);
        assertThat(snapshot.get("warranty_scope_snapshot")).isEqualTo("SELLER_NON_HUMAN_FUNCTIONAL_FAILURE");
        assertThat(snapshot.get("manufacturer_warranty_proof_snapshot")).isEqualTo("proof-v1");
        assertThat(((Timestamp) snapshot.get("manufacturer_warranty_expires_at")).toInstant()).isEqualTo(manufacturerExpiry);

        var envelope = jdbc.queryForMap("SELECT event_id,event_type,aggregate_id,aggregate_version,schema_version,payload FROM integration_outbox WHERE aggregate_id=?", orderId.toString());
        assertThat(UUID.fromString((String) envelope.get("event_id"))).isNotNull();
        assertThat(envelope.get("event_type")).isEqualTo("ORDER_CREATED");
        assertThat(envelope.get("aggregate_id")).isEqualTo(orderId.toString());
        assertThat(envelope.get("aggregate_version")).isEqualTo(1L);
        assertThat(envelope.get("schema_version")).isEqualTo(1);
        Map<String, Object> payload = objectMapper.readValue((String) envelope.get("payload"), new TypeReference<>() { });
        assertThat(payload).containsEntry("orderId", orderId.toString())
            .containsEntry("buyerId", buyer.toString()).containsEntry("sellerId", seller.toString())
            .containsEntry("listingId", listing.toString()).containsEntry("quantity", 1)
            .containsEntry("totalAmountFen", 1_200);
    }

    @Test
    void failedTransactionLeavesNoArtifactsAndSameKeyCanRetry() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 1, 777, null);
        String token = token(buyer);
        String key = "rollback-key-" + UUID.randomUUID();
        java.util.concurrent.atomic.AtomicReference<UUID> injectedOrderId = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            injectedOrderId.set(invocation.getArgument(0, UUID.class));
            throw new IllegalStateException("injected failure");
        }).when(orderCreationHook).afterOrderCreatedOutbox(org.mockito.ArgumentMatchers.any());
        try {
            assertThat(post(token, listing, 1, key).statusCode()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command WHERE actor_id=? AND idempotency_key=?", Integer.class, buyer.toString(), key)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=?", Integer.class, listing.toString())).isZero();
            assertThat(injectedOrderId.get()).as("故障注入必须捕获已写入 outbox 的 orderId").isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=?", Integer.class, injectedOrderId.get().toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        } finally {
            reset(orderCreationHook);
        }
        HttpResponse<String> retry = post(token, listing, 1, key);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command WHERE actor_id=? AND idempotency_key=?", Integer.class, buyer.toString(), key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=? AND order_id IS NOT NULL", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_CREATED' AND aggregate_id IN (SELECT id FROM trade_order WHERE listing_id=?)", Integer.class, listing.toString())).isEqualTo(1);
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
        return listing(seller, quantity, unitPrice, warrantyDays, null, null);
    }

    private UUID listing(UUID seller, int quantity, long unitPrice, Integer warrantyDays,
                         String manufacturerProof, Instant manufacturerExpiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,warranty_days,warranty_scope,manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,?,?,?,?, 'ON_SALE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), seller.toString(), "高等数学", "九成新", "教材", unitPrice, quantity, warrantyDays,
            warrantyDays == null ? null : "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE", manufacturerProof,
            manufacturerExpiresAt == null ? null : Timestamp.from(manufacturerExpiresAt));
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

    private static <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return future.get(45, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("异步请求未在限定时间内完成", e);
        }
    }
}
