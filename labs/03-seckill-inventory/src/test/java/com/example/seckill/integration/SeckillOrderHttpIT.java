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
        jdbcTemplate.update("DELETE FROM seckill_order");
        jdbcTemplate.update("UPDATE seckill_product SET stock = 3 WHERE id = 1");
    }

    @Test
    void allowsExactlyThreeDifferentUsersWhenStockIsThree() throws Exception {
        List<HttpResponse<String>> responses = postConcurrently(List.of(101L, 102L, 103L, 104L, 105L));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(201, 201, 201, 409, 409);
        assertThat(countOrders()).isEqualTo(3);
        assertThat(stock()).isEqualTo(0);
        assertThat(3).isEqualTo(stock() + countOrders());
        assertUtf8(responses);
    }

    @Test
    void allowsOnlyOneOrderWhenSameUserRequestsConcurrently() throws Exception {
        List<HttpResponse<String>> responses = postConcurrently(List.of(201L, 201L, 201L, 201L));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(201, 409, 409, 409);
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(2);
        assertThat(3).isEqualTo(stock() + countOrders());
        assertUtf8(responses);
    }

    @Test
    void rollsBackStockWhenDuplicateOrderIsRejected() throws Exception {
        HttpResponse<String> first = post(301L);
        assertThat(first.statusCode()).isEqualTo(201);
        jdbcTemplate.update("UPDATE seckill_product SET stock = 3 WHERE id = 1");

        HttpResponse<String> duplicate = post(301L);

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("ALREADY_PURCHASED");
        assertThat(duplicate.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValueSatisfying(
                value -> assertThat(value).contains("charset=UTF-8"));
        assertThat(countOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(3);
    }

    private List<HttpResponse<String>> postConcurrently(List<Long> users) throws Exception {
        var pool = Executors.newFixedThreadPool(users.size());
        var ready = new CountDownLatch(users.size());
        var start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (long user : users) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(user);
                }));
            }
            ready.await();
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> post(long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/seckill/orders"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"userId\":" + userId + ",\"productId\":1}", StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int stock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM seckill_product WHERE id = 1", Integer.class);
    }

    private int countOrders() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE product_id = 1", Integer.class);
    }

    private void assertUtf8(List<HttpResponse<String>> responses) {
        responses.forEach(response -> assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(value -> assertThat(value).contains("application/json").contains("charset=UTF-8")));
    }
}
