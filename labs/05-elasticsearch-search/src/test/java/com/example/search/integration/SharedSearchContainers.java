package com.example.search.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;

/** Shared real Elasticsearch service built from the SmartCN Dockerfile. */
@TestPropertySource(properties = {"search.startup.enabled=true", "search.scheduling.enabled=false"})
public abstract class SharedSearchContainers extends SharedMySqlContainer {
    static final GenericContainer<?> ELASTICSEARCH;
    static final ToxiproxyContainer TOXIPROXY;
    static final ToxiproxyContainer.ContainerProxy ELASTICSEARCH_PROXY;

    static {
        ELASTICSEARCH = new GenericContainer<>(new ImageFromDockerfile(
                "java-roadmap/elasticsearch-smartcn:8.18.8", false)
                .withFileFromPath("Dockerfile", Path.of("docker/elasticsearch/Dockerfile")))
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withNetwork(Network.SHARED)
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));
        ELASTICSEARCH.start();
        TOXIPROXY = new ToxiproxyContainer(
                org.testcontainers.utility.DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                .withNetwork(Network.SHARED);
        TOXIPROXY.start();
        ELASTICSEARCH_PROXY = TOXIPROXY.getProxy(ELASTICSEARCH, 9200);
        Runtime.getRuntime().addShutdownHook(new Thread(ELASTICSEARCH::stop,
                "shared-elasticsearch-container-shutdown"));
        Runtime.getRuntime().addShutdownHook(new Thread(TOXIPROXY::stop,
                "shared-toxiproxy-container-shutdown"));
    }

    @DynamicPropertySource
    static void registerElasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () ->
                "http://" + ELASTICSEARCH_PROXY.getContainerIpAddress() + ":" + ELASTICSEARCH_PROXY.getProxyPort());
    }

    protected static void setElasticConnectionCut(boolean cut) {
        ELASTICSEARCH_PROXY.setConnectionCut(cut);
    }

    protected static String directElasticsearchUri() {
        return "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200);
    }
}
