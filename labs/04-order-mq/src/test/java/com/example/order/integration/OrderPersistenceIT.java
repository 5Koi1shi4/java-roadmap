package com.example.order.integration;

import com.example.order.OrderMqApplication;
import com.example.order.application.CancellationResult;
import com.example.order.application.CreateOrderCommand;
import com.example.order.application.InsufficientStockException;
import com.example.order.application.OrderService;
import com.example.order.application.OrderTimeoutEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = OrderMqApplication.class, webEnvironment = WebEnvironment.NONE)
class OrderPersistenceIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("test");

    @Autowired
    OrderService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE order_stock SET available = 10 WHERE product_id = 1");
    }

    @Test
    void persistsOrderAndTimeoutEventAtomically() throws Exception {
        long orderId = service.createOrder(new CreateOrderCommand(1, 2));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbcTemplate.queryForObject("SELECT available FROM order_stock WHERE product_id = 1", Integer.class))
                .isEqualTo(8);
        String payload = jdbcTemplate.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id = ?", String.class, orderId);
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId", "eventType", "orderId", "occurredAt", "schemaVersion");
        assertThat(json.get("eventType").asText()).isEqualTo("ORDER_TIMEOUT");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id = ?", String.class, orderId))
                .isEqualTo("NEW");
    }

    @Test
    void rollsBackStockWhenOrderCannotBeCreated() {
        jdbcTemplate.update("UPDATE order_stock SET available = 0 WHERE product_id = 1");

        assertThatThrownBy(() -> service.createOrder(new CreateOrderCommand(1, 1)))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    void cancelsPendingOrderAndReleasesStockAtMostOnce() {
        long orderId = service.createOrder(new CreateOrderCommand(1, 2));
        OrderTimeoutEvent event = new OrderTimeoutEvent(UUID.randomUUID(), "ORDER_TIMEOUT", orderId, Instant.now(), 1);

        assertThat(service.cancelExpired(event)).isEqualTo(CancellationResult.CANCELLED);
        assertThat(service.cancelExpired(event)).isEqualTo(CancellationResult.ALREADY_CANCELLED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT available FROM order_stock WHERE product_id = 1", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void doesNotCancelPaidOrder() {
        long orderId = service.createOrder(new CreateOrderCommand(1, 1));
        service.pay(new com.example.order.application.PayOrderCommand(orderId));
        OrderTimeoutEvent event = new OrderTimeoutEvent(UUID.randomUUID(), "ORDER_TIMEOUT", orderId, Instant.now(), 1);

        assertThat(service.cancelExpired(event)).isEqualTo(CancellationResult.ALREADY_PAID);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT available FROM order_stock WHERE product_id = 1", Integer.class))
                .isEqualTo(9);
    }
}
