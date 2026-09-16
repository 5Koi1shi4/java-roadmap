package com.example.campusmarket.integration;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.util.stream.Stream;

/** 认证流程只需要 MySQL 与 Redis，避免加载共享集成测试的重型依赖。 */
abstract class AuthFlowContainers {
    private static final Network NETWORK = Network.newNetwork();

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market")
        .withUsername("campus_market")
        .withPassword("campus_market_local")
        .withNetwork(NETWORK)
        .withNetworkAliases("mysql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK)
        .withNetworkAliases("redis")
        .withExposedPorts(6379);

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }

    @DynamicPropertySource
    static void registerAuthFlowContainerProperties(DynamicPropertyRegistry registry) {
        ResourceServerTestSupport.register(registry);
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> "1");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1");
        registry.add("campus.market.storage.endpoint", () -> "http://127.0.0.1:1");

        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("campus.market.search.dispatcher.enabled", () -> "false");
        registry.add("campus.market.order.deadline.enabled", () -> "false");
        registry.add("campus.market.warranty.deadline.enabled", () -> "false");
        registry.add("campus.market.dispute.deadline.enabled", () -> "false");
        registry.add("campus.market.dispute.return-reconciliation.enabled", () -> "false");
        registry.add("campus.market.payment.reconciliation.enabled", () -> "false");
    }

    @AfterAll
    static void stopAuthFlowContainers() {
        if (REDIS.isRunning()) REDIS.stop();
        if (MYSQL.isRunning()) MYSQL.stop();
        NETWORK.close();
    }
}
