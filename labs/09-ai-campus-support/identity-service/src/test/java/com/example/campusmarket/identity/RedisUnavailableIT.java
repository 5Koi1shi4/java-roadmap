package com.example.campusmarket.identity;

import com.example.campusmarket.testsupport.TestRsaKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立 Redis 故障流程，不与正常 AuthFlow 共享容器或测试顺序。 */
@SpringBootTest(classes = IdentityServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class RedisUnavailableIT {
    private static final Network NETWORK = Network.newNetwork();
    private static final Path KEY_DIRECTORY = createKeys();
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("identity_db")
        .withUsername("identity_runtime")
        .withPassword("identity_runtime_local")
        .withNetwork(NETWORK)
        .withNetworkAliases("redis-failure-mysql");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK)
        .withNetworkAliases("redis-failure-redis")
        .withExposedPorts(6379);

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("campus.market.jwt.private-key", () -> keyUri("test-private-key.pem"));
        registry.add("campus.market.jwt.public-key", () -> keyUri("test-public-key.pem"));
        registry.add("spring.task.scheduling.enabled", () -> "false");
    }

    @AfterAll
    static void stopContainers() {
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
        NETWORK.close();
    }

    @Test
    void returns503WithinBoundedTimeWhenRedisIsUnavailable() throws Exception {
        REDIS.stop();
        HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/auth/email-verifications"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"email\":\"student-" + System.nanoTime() + "@stu.example.edu.cn\","
                    + "\"purpose\":\"REGISTER\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/json")
            .containsIgnoringCase("utf-8");
        assertThat(response.body()).contains("身份服务暂时不可用").doesNotContain("localhost", "6379");
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
            Path directory = Files.createTempDirectory("identity-redis-failure-keys");
            TestRsaKeys.writePemPair(directory);
            return directory;
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建身份测试密钥", ex);
        }
    }
}
