package com.example.seckill.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeckillOrderHttpIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM seckill_order");
        jdbcTemplate.update("UPDATE seckill_product SET stock = 3 WHERE id = 1");
    }

    @Test
    void replaysExactlyTheSameUtf8JsonForConcurrentRequestsWithTheSameKey() throws Exception {
        List<HttpResponse<byte[]>> responses = postConcurrently("same-key", List.of(401L, 401L, 401L, 401L));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsOnly(201);
        assertThat(responses).extracting(this::decodeUtf8)
                .containsOnly(decodeUtf8(responses.get(0)));
        assertThat(decodeUtf8(responses.get(0))).contains("下单成功");
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    void differentKeysStillCreateOnlyOneOrderForTheSameUser() throws Exception {
        List<HttpResponse<byte[]>> responses = postConcurrently(
                List.of("different-key-a", "different-key-b"), List.of(402L, 402L));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(201, 409);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409)
                .findFirst().map(this::decodeUtf8).orElseThrow()).contains("已购买");
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    void removesIdempotencyRecordAfterSystemFailureSoTheSameKeyCanRetry() throws Exception {
        jdbcTemplate.execute("RENAME TABLE seckill_order TO seckill_order_backup");
        try {
            HttpResponse<byte[]> failed = post("system-failure-key", 403L);
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Integer.class,
                    "system-failure-key")).isZero();
        } finally {
            jdbcTemplate.execute("RENAME TABLE seckill_order_backup TO seckill_order");
        }

        HttpResponse<byte[]> retried = post("system-failure-key", 403L);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(decodeUtf8(retried)).contains("下单成功");
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    void allowsExactlyThreeDifferentUsersWhenStockIsThree() throws Exception {
        List<HttpResponse<byte[]>> responses = postConcurrently(List.of(101L, 102L, 103L, 104L, 105L));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(201, 201, 201, 409, 409);
        assertThat(countOrders()).isEqualTo(3);
        assertThat(stock()).isEqualTo(0);
        assertThat(3).isEqualTo(stock() + countOrders());
        assertUtf8(responses);
    }

    @Test
    void allowsOnlyOneOrderWhenSameUserRequestsConcurrently() throws Exception {
        List<HttpResponse<byte[]>> responses = postConcurrently(List.of(201L, 201L, 201L, 201L));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(201, 409, 409, 409);
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(2);
        assertThat(3).isEqualTo(stock() + countOrders());
        assertUtf8(responses);
    }

    @Test
    void rollsBackStockWhenDuplicateOrderIsRejected() throws Exception {
        HttpResponse<byte[]> first = post(301L);
        assertThat(first.statusCode()).isEqualTo(201);
        jdbcTemplate.update("UPDATE seckill_product SET stock = 3 WHERE id = 1");

        HttpResponse<byte[]> duplicate = post(301L);

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(decodeUtf8(duplicate)).contains("ALREADY_PURCHASED").contains("已购买");
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(3);
    }

    private List<HttpResponse<byte[]>> postConcurrently(List<Long> users) throws Exception {
        List<String> keys = java.util.stream.IntStream.range(0, users.size())
                .mapToObj(index -> "user-key-" + users.get(index) + "-" + index).toList();
        return postConcurrently(keys, users);
    }

    private List<HttpResponse<byte[]>> postConcurrently(String key, List<Long> users) throws Exception {
        return postConcurrently(users.stream().map(user -> key).toList(), users);
    }

    private List<HttpResponse<byte[]>> postConcurrently(List<String> keys, List<Long> users) throws Exception {
        var pool = Executors.newFixedThreadPool(users.size());
        var ready = new CountDownLatch(users.size());
        var start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int index = 0; index < users.size(); index++) {
                long user = users.get(index);
                String key = keys.get(index);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(key, user);
                }));
            }
            ready.await();
            start.countDown();
            List<HttpResponse<byte[]>> responses = new ArrayList<>();
            for (Future<HttpResponse<byte[]>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<byte[]> post(long userId) throws Exception {
        return post("legacy-user-key-" + userId + "-" + System.nanoTime(), userId);
    }

    private HttpResponse<byte[]> post(String key, long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/seckill/orders"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"userId\":" + userId + ",\"productId\":1}", StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private int stock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM seckill_product WHERE id = 1", Integer.class);
    }

    private int countOrders() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id = 1", Integer.class);
    }

    private void assertUtf8(List<HttpResponse<byte[]>> responses) {
        responses.forEach(response -> {
            Charset charset = declaredCharset(response);
            assertThat(charset).isEqualTo(StandardCharsets.UTF_8);
        });
    }

    private String decodeUtf8(HttpResponse<byte[]> response) {
        return new String(response.body(), declaredCharset(response));
    }

    private Charset declaredCharset(HttpResponse<?> response) {
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");
        MediaType mediaType = MediaType.parseMediaType(contentType);
        assertThat(mediaType.getType()).isEqualTo("application");
        assertThat(mediaType.getSubtype()).isEqualTo("json");
        Charset charset = mediaType.getCharset();
        assertThat(charset).isNotNull();
        return charset;
    }
}
