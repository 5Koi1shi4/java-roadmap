package com.example.campusmarket.integration;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/** 最终旅程的最小真实依赖：MySQL、Redis、SmartCN ES 和 MinIO；支付模拟器是应用内 HTTP 端点。 */
abstract class JourneyContainers {
    private static final Network NETWORK = Network.newNetwork();
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
        .withNetwork(NETWORK).withNetworkAliases("mysql");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
    private static final ImageFromDockerfile ES_IMAGE = new ImageFromDockerfile(
        "campus-market/elasticsearch:8.18.8-smartcn", true)
        .withDockerfile(Path.of("docker/elasticsearch/Dockerfile"));
    protected static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8"))
        .withEnv("xpack.security.enabled", "false")
        .withNetwork(NETWORK).withNetworkAliases("elasticsearch");
    protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
        "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
        .withCommand("server /data --console-address :9001")
        .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
        .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000, 9001);

    static {
        ELASTICSEARCH.setImage(ES_IMAGE);
        Startables.deepStart(Stream.of(MYSQL, REDIS, ELASTICSEARCH, MINIO)).join();
    }

    @DynamicPropertySource
    static void registerJourneyProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
        registry.add("campus.market.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> "1");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
    }

    @AfterAll
    static void stopJourneyContainers() {
        Stream.of(MINIO, ELASTICSEARCH, REDIS, MYSQL).filter(Objects::nonNull)
            .forEach(GenericContainer::stop);
        NETWORK.close();
    }
}
