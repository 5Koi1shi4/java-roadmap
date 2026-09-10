package com.example.campusmarket.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.stream.Stream;

/** 所有集成测试共用的六个隔离外部服务容器。 */
@SpringBootTest
public abstract class SharedContainers {
    private static final Network NETWORK = Network.newNetwork();

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market")
        .withUsername("campus_market")
        .withPassword("campus_market_local")
        .withNetwork(NETWORK)
        .withNetworkAliases("mysql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK)
        .withNetworkAliases("redis")
        .withExposedPorts(6379);

    protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:3.13.7-management"))
        .withNetwork(NETWORK)
        .withNetworkAliases("rabbitmq");

    private static final ImageFromDockerfile ELASTICSEARCH_IMAGE = new ImageFromDockerfile(
        "campus-market/elasticsearch:8.18.8-smartcn", true)
        .withDockerfile(Path.of("docker/elasticsearch/Dockerfile"));
    protected static final ElasticsearchContainer ELASTICSEARCH = elasticsearchContainer();

    protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
        "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
        .withCommand("server /data --console-address :9001")
        .withEnv("MINIO_ROOT_USER", "minioadmin")
        .withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
        .withNetwork(NETWORK)
        .withNetworkAliases("minio")
        .withExposedPorts(9000, 9001);

    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
        DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
        .withNetwork(NETWORK)
        .withNetworkAliases("toxiproxy")
        .withAccessToHost(true);
    /** 可被 Toxiproxy 注入断连的真实 HTTP provider 边界。 */
    protected static final GenericContainer<?> PAYMENT_PROVIDER_HTTP = new GenericContainer<>(DockerImageName.parse("python:3.12-alpine"))
        .withCommand("sh", "-c", "mkdir -p /www/payments && printf '{\"providerReference\":\"probe\",\"status\":\"UNKNOWN\",\"amountFen\":0}' > /www/payments/probe && python -m http.server 8080 --directory /www")
        .withNetwork(NETWORK)
        .withNetworkAliases("payment-provider-http")
        .withExposedPorts(8080)
        .waitingFor(Wait.forListeningPort());
    protected static ToxiproxyContainer.ContainerProxy MINIO_PROXY;
    protected static ToxiproxyContainer.ContainerProxy ELASTICSEARCH_PROXY;

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS, RABBITMQ, ELASTICSEARCH, MINIO, TOXIPROXY, PAYMENT_PROVIDER_HTTP)).join();
        MINIO_PROXY = TOXIPROXY.getProxy(MINIO, 9000);
        ELASTICSEARCH_PROXY = TOXIPROXY.getProxy(ELASTICSEARCH, 9200);
    }

    private static ElasticsearchContainer elasticsearchContainer() {
        DockerImageName compatibleImage = DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8");
        ElasticsearchContainer container = SharedElasticsearchContainerFactory.create(compatibleImage)
            .withNetwork(NETWORK)
            .withNetworkAliases("elasticsearch");
        container.setImage(ELASTICSEARCH_IMAGE);
        return container;
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH_PROXY.getContainerIpAddress() + ":" + ELASTICSEARCH_PROXY.getProxyPort());
        registry.add("campus.market.storage.endpoint", () -> "http://" + MINIO_PROXY.getContainerIpAddress() + ":" + MINIO_PROXY.getProxyPort());
    }
}
