package com.example.campusmarket.integration;

import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.stream.Stream;

/** 证据 HTTP 测试的最小真实依赖：MySQL、MinIO 与 MinIO 故障代理。 */
abstract class DisputeEvidenceContainers {
    private static final Network NETWORK = Network.newNetwork();
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
        .withNetwork(NETWORK).withNetworkAliases("mysql");
    protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
        "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
        .withCommand("server /data --console-address :9001")
        .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
        .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000, 9001)
        .waitingFor(Wait.forListeningPort());
    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
        DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
        .withNetwork(NETWORK).withNetworkAliases("toxiproxy").withAccessToHost(true);
    protected static ToxiproxyContainer.ContainerProxy MINIO_PROXY;

    static {
        Startables.deepStart(Stream.of(MYSQL, MINIO, TOXIPROXY)).join();
        MINIO_PROXY = TOXIPROXY.getProxy(MINIO, 9000);
    }

    @AfterAll
    static void stopEvidenceContainers() {
        Stream.of(TOXIPROXY, MINIO, MYSQL).forEach(GenericContainer::stop);
        NETWORK.close();
    }

    @DynamicPropertySource
    static void registerEvidenceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("campus.market.storage.endpoint", () -> "http://" + MINIO_PROXY.getContainerIpAddress() + ":" + MINIO_PROXY.getProxyPort());
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("campus.market.search.dispatcher.enabled", () -> "false");
        registry.add("campus.market.payment.reconciliation.enabled", () -> "false");
    }
}
