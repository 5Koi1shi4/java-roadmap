package com.example.order.integration;

import com.example.order.OrderMqApplication;
import com.example.order.application.CreateOrderCommand;
import com.example.order.application.PayOrderCommand;
import com.example.order.application.OrderService;
import com.example.order.infrastructure.mq.OutboxDispatcher;
import com.example.order.infrastructure.mq.RabbitTopologyConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrderMqApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReliableMessagingFlowIT {
    @Autowired
    OrderService service;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
        registry.add("order.timeout.routing-key", () -> "order.timeout.10s");
    }

    @BeforeEach
    void isolate() {
        SharedContainers.cleanDatabase(jdbcTemplate);
        SharedContainers.purgeQueues(rabbitAdmin);
    }

    @Test
    void publishesTimeoutAndEventuallyCancelsOnlyPendingOrder() {
        long orderId = service.createOrder(new CreateOrderCommand(1, 1));
        dispatcher.dispatchOnce();

        assertThat(statusOfOutbox(orderId)).isEqualTo("PUBLISHED");

        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(statusOf(orderId)).isEqualTo("CANCELLED"));
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void paidOrderSurvivesLateTimeoutEvent() throws Exception {
        long orderId = service.createOrder(new CreateOrderCommand(1, 1));
        service.pay(new PayOrderCommand(orderId));
        dispatcher.dispatchOnce();

        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(consumedMessageCount(eventIdFor(orderId))).isEqualTo(1));
        assertThat(consumedMessageStatus(eventIdFor(orderId))).isEqualTo("COMPLETED");
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void duplicateEventIdReleasesStockOnlyOnce() throws Exception {
        long orderId = service.createOrder(new CreateOrderCommand(1, 1));
        dispatcher.dispatchOnce();
        String eventId = eventIdFor(orderId);
        jdbcTemplate.update("UPDATE outbox_event SET status = 'NEW', published_at = NULL "
                        + "WHERE aggregate_id = ?", orderId);
        dispatcher.dispatchOnce();

        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(statusOf(orderId)).isEqualTo("CANCELLED"));
        assertThat(stock()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_message WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void retryableTimeoutFailureReachesManualQueueAfterThreeAttempts() throws Exception {
        UUID eventId = UUID.randomUUID();
        long orderId = service.createOrder(new CreateOrderCommand(1, 1));
        String payload = objectMapper.writeValueAsString(
                new com.example.order.application.OrderTimeoutEvent(
                        eventId, "ORDER_TIMEOUT", orderId, Instant.now(), 1));
        jdbcTemplate.update("INSERT INTO consumed_message (event_id, status, lease_until, claim_token) "
                        + "VALUES (?, 'PROCESSING', DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), ?)",
                eventId.toString(), UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend("", RabbitTopologyConfiguration.CANCEL_QUEUE,
                new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        Message manual = Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> rabbitTemplate.receive(RabbitTopologyConfiguration.MANUAL_QUEUE),
                        message -> message != null);
        assertThat(manual.getMessageProperties().getHeaders().get("failure-category"))
                .isEqualTo("RETRYABLE");
        assertThat(manual.getMessageProperties().getHeaders().get("x-retry-count"))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumed_message WHERE event_id = ?", String.class, eventId.toString()))
                .isEqualTo("FAILED");
    }

    private String payloadFor(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE aggregate_id = ?", String.class, orderId);
    }

    private String eventIdFor(long orderId) throws Exception {
        JsonNode payload = objectMapper.readTree(payloadFor(orderId));
        return payload.get("eventId").asText();
    }

    private String statusOf(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String statusOfOutbox(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event WHERE aggregate_id = ?", String.class, orderId);
    }

    private int stock() {
        return jdbcTemplate.queryForObject(
                "SELECT available FROM order_stock WHERE product_id = 1", Integer.class);
    }

    private int consumedMessageCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_message WHERE event_id = ?", Integer.class, eventId);
    }

    private String consumedMessageStatus(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM consumed_message WHERE event_id = ?", String.class, eventId);
    }
}
