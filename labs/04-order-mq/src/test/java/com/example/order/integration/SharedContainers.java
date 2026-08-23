package com.example.order.integration;

import com.example.order.infrastructure.mq.RabbitTopologyConfiguration;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/** One pair of real brokers shared by every integration test in this module. */
final class SharedContainers {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("test");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management")
            .withUser("order_mq", "test")
            .withVhost("/");

    static {
        MYSQL.start();
        RABBIT.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RABBIT.stop();
            MYSQL.stop();
        }, "order-mq-testcontainers-shutdown"));
    }

    private SharedContainers() {
    }

    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "order_mq");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    static void cleanDatabase(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM consumed_message");
        jdbcTemplate.update("DELETE FROM manual_failure");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE order_stock SET available = 10 WHERE product_id = 1");
    }

    static void purgeQueues(RabbitAdmin rabbitAdmin) {
        String[] queues = {
                RabbitTopologyConfiguration.TIMEOUT_QUEUE_10S,
                RabbitTopologyConfiguration.TIMEOUT_QUEUE_1M,
                RabbitTopologyConfiguration.TIMEOUT_QUEUE_5M,
                RabbitTopologyConfiguration.CANCEL_QUEUE,
                RabbitTopologyConfiguration.MANUAL_QUEUE
        };
        for (String queue : queues) {
            if (rabbitAdmin.getQueueProperties(queue) != null) {
                rabbitAdmin.purgeQueue(queue, true);
            }
        }
    }
}
