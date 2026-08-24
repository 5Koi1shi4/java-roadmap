package com.example.search.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;

/** Shared real Elasticsearch service built from the SmartCN Dockerfile. */
public abstract class SharedSearchContainers extends SharedMySqlContainer {
    static final GenericContainer<?> ELASTICSEARCH;

    static {
        ELASTICSEARCH = new GenericContainer<>(new ImageFromDockerfile(
                "java-roadmap/elasticsearch-smartcn:8.18.8", false)
                .withFileFromPath("Dockerfile", Path.of("docker/elasticsearch/Dockerfile")))
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));
        ELASTICSEARCH.start();
        Runtime.getRuntime().addShutdownHook(new Thread(ELASTICSEARCH::stop,
                "shared-elasticsearch-container-shutdown"));
    }

    @DynamicPropertySource
    static void registerElasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () ->
                "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }
}
