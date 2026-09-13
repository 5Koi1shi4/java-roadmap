package com.example.campusmarket.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Small messaging fixture: only MySQL and Rabbit are started for Task 12.
 * Redis, Elasticsearch, MinIO and Toxiproxy are deliberately out of scope. */
@Testcontainers
public abstract class Task12RabbitMySqlContainers {
    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local");
    @Container
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:3.13.7-management"));

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        ResourceServerTestSupport.register(registry);
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("campus.market.search.dispatcher.enabled", () -> "false");
        registry.add("campus.market.dispute.deadline.enabled", () -> "false");
        registry.add("campus.market.dispute.return-reconciliation.enabled", () -> "false");
        registry.add("campus.market.warranty.deadline.enabled", () -> "false");
    }
}
