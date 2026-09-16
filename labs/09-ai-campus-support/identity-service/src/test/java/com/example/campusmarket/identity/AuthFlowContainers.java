package com.example.campusmarket.identity;

import com.example.campusmarket.testsupport.TestRsaKeys;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** 身份服务 HTTP 流程只启动独立 MySQL 与 Redis。 */
abstract class AuthFlowContainers {
    private static final Network NETWORK = Network.newNetwork();
    private static final Path KEY_DIRECTORY = createKeys();

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("identity_db")
        .withUsername("identity_runtime")
        .withPassword("identity_runtime_local")
        .withNetwork(NETWORK)
        .withNetworkAliases("identity-mysql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK)
        .withNetworkAliases("identity-redis")
        .withExposedPorts(6379);

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }

    @DynamicPropertySource
    static void registerAuthFlowContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("campus.market.jwt.private-key", () -> keyUri("test-private-key.pem"));
        registry.add("campus.market.jwt.public-key", () -> keyUri("test-public-key.pem"));
        registry.add("spring.task.scheduling.enabled", () -> "false");
    }

    @AfterAll
    static void stopAuthFlowContainers() {
        if (REDIS.isRunning()) REDIS.stop();
        if (MYSQL.isRunning()) MYSQL.stop();
        NETWORK.close();
    }

    static Path keyDirectory() {
        return KEY_DIRECTORY;
    }

    private static String keyUri(String name) {
        try {
            return new org.springframework.core.io.FileSystemResource(KEY_DIRECTORY.resolve(name))
                .getURI().toString();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("无法读取测试密钥 URI", ex);
        }
    }

    private static Path createKeys() {
        try {
            Path directory = Files.createTempDirectory("identity-auth-flow-keys");
            TestRsaKeys.writePemPair(directory);
            return directory;
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建身份测试密钥", ex);
        }
    }
}
