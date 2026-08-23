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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = OrderMqApplication.class, webEnvironment = WebEnvironment.NONE)
class OrderTimeoutIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management")
            .withUser("order_mq", "test")
            .withVhost("/");

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "order_mq");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM consumed_message");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE order_stock SET available = 10 WHERE product_id = 1");
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
}
