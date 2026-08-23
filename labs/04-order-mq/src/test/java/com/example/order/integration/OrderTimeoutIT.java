package com.example.order.integration;

import com.example.order.OrderMqApplication;
import com.example.order.application.CreateOrderCommand;
import com.example.order.application.OrderService;
import com.example.order.application.OrderTimeoutEvent;
import com.example.order.infrastructure.mq.RabbitTopologyConfiguration;
import com.example.order.infrastructure.mq.OrderTimeoutConsumer;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrderMqApplication.class, webEnvironment = WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderTimeoutIT {
    @Autowired
    OrderService orderService;

    @Autowired
    OrderTimeoutConsumer consumer;

    @Autowired
    JdbcOrderRepository repository;

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
    }

    @BeforeEach
    void cleanDatabase() {
        SharedContainers.cleanDatabase(jdbcTemplate);
        SharedContainers.purgeQueues(rabbitAdmin);
    }

    @Test
    void duplicateEventIdCancelsAndReleasesStockOnlyOnce() {
        long orderId = orderService.createOrder(new CreateOrderCommand(1, 1));
        OrderTimeoutEvent event = new OrderTimeoutEvent(
                UUID.randomUUID(), "ORDER_TIMEOUT", orderId, Instant.now(), 1);

        consumer.handle(event);
        consumer.handle(event);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT available FROM order_stock WHERE product_id = 1", Integer.class))
                .isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumed_message WHERE event_id = ?", String.class, event.eventId().toString()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void declaresTtlBucketsAndDeadLetterDestination() {
        assertThat(rabbitAdmin.getQueueProperties(RabbitTopologyConfiguration.TIMEOUT_QUEUE_10S)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(RabbitTopologyConfiguration.TIMEOUT_QUEUE_1M)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(RabbitTopologyConfiguration.TIMEOUT_QUEUE_5M)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(RabbitTopologyConfiguration.CANCEL_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(RabbitTopologyConfiguration.MANUAL_QUEUE)).isNotNull();
    }

    @Test
    void realRabbitTtlDlxConsumerCancelsAndDeduplicates() throws Exception {
        long orderId = orderService.createOrder(new CreateOrderCommand(1, 1));
        OrderTimeoutEvent event = new OrderTimeoutEvent(
                UUID.randomUUID(), "ORDER_TIMEOUT", orderId, Instant.now(), 1);
        String payload = objectMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(RabbitTopologyConfiguration.TIMEOUT_EXCHANGE,
                "order.timeout.10s", payload);
        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                    .isEqualTo("CANCELLED");
        });
        assertThat(jdbcTemplate.queryForObject("SELECT available FROM order_stock WHERE product_id = 1", Integer.class))
                .isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumed_message WHERE event_id = ?", String.class, event.eventId().toString()))
                .isEqualTo("COMPLETED");

        rabbitTemplate.convertAndSend(RabbitTopologyConfiguration.TIMEOUT_EXCHANGE,
                "order.timeout.10s", payload);
        Thread.sleep(1_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT available FROM order_stock WHERE product_id = 1", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void nonRecoverableMessageIsPublishedToManualQueueWithOneAttempt() throws Exception {
        rabbitTemplate.convertAndSend(RabbitTopologyConfiguration.TIMEOUT_EXCHANGE,
                "order.timeout.10s", "{broken-json");

        org.springframework.amqp.core.Message manual = Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .until(() -> rabbitTemplate.receive(RabbitTopologyConfiguration.MANUAL_QUEUE),
                        message -> message != null);
        assertThat(manual.getMessageProperties().getHeaders().get("failure-category"))
                .isEqualTo("NON_RETRYABLE");
        assertThat(manual.getMessageProperties().getHeaders().get("x-retry-count"))
                .isEqualTo(1);
    }

    @Test
    void completedFailureRecordRemainsCompleted() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO consumed_message "
                        + "(event_id, status, failure_category, last_error, completed_at) "
                        + "VALUES (?, 'COMPLETED', NULL, NULL, UTC_TIMESTAMP(6))",
                eventId.toString());

        repository.recordConsumptionFailure(eventId, "NON_RETRYABLE", "late failure", Instant.now());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumed_message WHERE event_id = ?", String.class, eventId.toString()))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_category FROM consumed_message WHERE event_id = ?", String.class, eventId.toString()))
                .isNull();
    }

    @Test
    void failedConsumptionIsTerminal() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO consumed_message (event_id, status, failure_category, last_error) "
                        + "VALUES (?, 'FAILED', 'NON_RETRYABLE', 'bad')", eventId.toString());
        OrderTimeoutEvent event = new OrderTimeoutEvent(
                eventId, "ORDER_TIMEOUT", 999_999L, Instant.now(), 1);

        consumer.handle(event);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumed_message WHERE event_id = ?", String.class, eventId.toString()))
                .isEqualTo("FAILED");
    }
}
