package com.example.campusmarket.integration;

import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/** 创建共享 SmartCN ES 容器的纯配置工厂；不会启动容器或创建共享生命周期。 */
public final class SharedElasticsearchContainerFactory {
    private static final long ELASTICSEARCH_MEMORY_BYTES = 768L * 1024L * 1024L;

    private SharedElasticsearchContainerFactory() {}

    public static ElasticsearchContainer create(DockerImageName compatibleImage) {
        return new ElasticsearchContainer(compatibleImage)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(ELASTICSEARCH_MEMORY_BYTES));
    }
}
