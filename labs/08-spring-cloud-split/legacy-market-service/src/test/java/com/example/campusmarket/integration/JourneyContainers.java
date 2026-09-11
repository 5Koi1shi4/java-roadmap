package com.example.campusmarket.integration;

import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import com.example.campusmarket.identity.infrastructure.LocalVerificationMailSender;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentProviderController;
import com.example.campusmarket.warranty.application.SellerObligationService;
import com.example.campusmarket.warranty.application.WarrantyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.core.io.ByteArrayResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP 旅程共用的断言与 fixture；具体容器由每个阶段的基类独立管理。 */
abstract class JourneyHttpSupport {
    protected static final String PAYMENT_SECRET = "local-only-payment-secret-change-me";

    @Autowired protected TestRestTemplate http;
    @Autowired protected LocalVerificationMailSender mail;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected SearchOutboxDispatcher searchOutbox;
    @Autowired protected ProductSearchPort search;
    @Autowired protected SimulatedPaymentProviderController provider;
    @Autowired protected WarrantyService warranties;
    @Autowired protected SellerObligationService obligations;
    @Autowired protected JwtService jwt;

    @BeforeEach
    void verifyDatabaseIsReady() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    protected User register(String email) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/api/auth/email-verifications",
            entity("{\"email\":\"" + email + "\"}", headers), String.class).getStatusCode())
            .isEqualTo(org.springframework.http.HttpStatus.OK);
        String code = mail.latestCode(email);
        assertThat(code).isNotBlank();
        assertThat(http.postForEntity("/api/auth/register",
            entity("{\"email\":\"" + email + "\",\"password\":\"Campus123!\",\"code\":\"" + code + "\"}", headers), String.class).getStatusCode())
            .isEqualTo(org.springframework.http.HttpStatus.CREATED);
        ResponseEntity<String> loginResponse = http.postForEntity("/api/auth/login",
            entity("{\"email\":\"" + email + "\",\"password\":\"Campus123!\"}", headers), String.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        JsonNode login = mapper.readTree(loginResponse.getBody());
        return new User(UUID.fromString(login.get("userId").asText()), login.get("accessToken").asText(), headers);
    }

    protected User seededAdmin() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@admin.example.edu.cn", "hash");
        return new User(id, jwt.issue(new AuthenticatedUser(id, java.util.Set.of("ROLE_ADMIN"))), null);
    }

    protected HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    protected HttpHeaders withKey(HttpHeaders source, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(source);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }

    protected HttpEntity<String> entity(String body, HttpHeaders headers) {
        return new HttpEntity<>(body, headers);
    }

    protected ResponseEntity<String> uploadListingMedia(UUID listing, User seller) {
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(png) {
            @Override public String getFilename() { return "cover.png"; }
        });
        HttpHeaders headers = bearer(seller.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/listings/" + listing + "/media", new HttpEntity<>(form, headers), String.class);
    }

    protected void callback(String type, UUID order, String reference, long amount, String status, String event) throws Exception {
        String body = "{\"providerEventId\":\"" + event + "\",\"type\":\"" + type
            + "\",\"providerReference\":\"" + reference + "\",\"amountFen\":" + amount
            + ",\"status\":\"" + status + "\",\"occurredAt\":\"" + Instant.now()
            + "\",\"orderId\":\"" + order + "\"}";
        long now = Instant.now().getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Payment-Timestamp", Long.toString(now));
        headers.set("X-Payment-Nonce", UUID.randomUUID().toString());
        headers.set("X-Payment-Signature", hmac(now + "\n" + headers.getFirst("X-Payment-Nonce") + "\n" + body));
        assertThat(http.postForEntity("/api/payment-webhooks/simulated", new HttpEntity<>(body, headers), String.class).getStatusCode())
            .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    protected String hmac(String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PAYMENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    protected String refundsReference(UUID id) {
        return jdbc.queryForObject("SELECT provider_reference FROM refund_order WHERE id=?", String.class, id.toString());
    }

    protected UUID uuid(String body, String field) throws Exception {
        return UUID.fromString(mapper.readTree(body).get(field).asText());
    }

    protected String text(String body, String field) throws Exception {
        return mapper.readTree(body).get(field).asText();
    }

    protected record User(UUID id, String token, HttpHeaders ignored) {
        HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            return headers;
        }
    }
}

/**
 * 教材旅程的分阶段真实依赖：MySQL、Redis、MinIO 与 SmartCN Elasticsearch 不重叠运行。
 *
 * Spring 上下文启动前只启动数据库、缓存和对象存储；媒体 HTTP 上传完成后释放 MinIO，
 * 再在固定 localhost 端口启动 ES。ES 客户端是懒连接，因此同一应用上下文可以继续完成
 * 发布、Outbox 投影和搜索，同时避免低内存主机上两个重型容器重叠。
 */
abstract class TextbookContainers extends JourneyHttpSupport {
    private static final int TEXTBOOK_ELASTICSEARCH_PORT = 19200;
    private static final long TEXTBOOK_ELASTICSEARCH_MEMORY_BYTES = 768L * 1024L * 1024L;
    private static final Network NETWORK = Network.newNetwork();
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
        .withNetwork(NETWORK).withNetworkAliases("mysql");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
    private static final ImageFromDockerfile ES_IMAGE = new ImageFromDockerfile(
        "campus-market/elasticsearch:8.18.8-smartcn", true).withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));
    protected static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(TEXTBOOK_ELASTICSEARCH_MEMORY_BYTES))
        .withNetwork(NETWORK).withNetworkAliases("elasticsearch");
    protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
        "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
        .withCommand("server /data --console-address :9001")
        .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
        .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000, 9001)
        .waitingFor(Wait.forListeningPort());

    static {
        ELASTICSEARCH.setImage(ES_IMAGE);
        ELASTICSEARCH.setPortBindings(java.util.List.of(TEXTBOOK_ELASTICSEARCH_PORT + ":9200"));
        Startables.deepStart(Stream.of(MYSQL, REDIS, MINIO)).join();
    }

    @DynamicPropertySource
    static void registerTextbookProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:" + TEXTBOOK_ELASTICSEARCH_PORT);
        registry.add("campus.market.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        JourneyDependencyProperties.registerCommonDisabledDependencies(registry);
    }

    static void stopTextbookContainers() {
        Stream.of(MINIO, ELASTICSEARCH, REDIS, MYSQL).filter(Objects::nonNull).forEach(GenericContainer::stop);
        NETWORK.close();
    }

    /** 在媒体已通过 HTTP 写入对象存储后切换到搜索阶段，确保 MinIO 与 ES 不重叠。 */
    protected static void startTextbookSearchContainer() {
        if (!ELASTICSEARCH.isRunning()) ELASTICSEARCH.start();
    }

    /**
     * 释放媒体阶段及注册阶段才需要的 Redis；停止后用有界屏障确认不会与 ES 启动重叠。
     * 幂等 stop 允许测试失败后由 @AfterAll 再次清理。
     */
    protected static void stopTextbookMediaContainer() {
        stopAndAwait(MINIO);
        stopAndAwait(REDIS);
    }

    private static void stopAndAwait(GenericContainer<?> container) {
        if (container.isRunning()) container.stop();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (container.isRunning() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("容器停止屏障被中断", interrupted);
            }
        }
        if (container.isRunning()) throw new IllegalStateException("容器未在有界时间内停止");
    }
}

/** 质保旅程的最小真实依赖：MySQL、Redis 和 MinIO。 */
abstract class WarrantyContainers extends JourneyHttpSupport {
    private static final Network NETWORK = Network.newNetwork();
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
        .withNetwork(NETWORK).withNetworkAliases("mysql");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
        .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
    protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
        "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
        .withCommand("server /data --console-address :9001")
        .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
        .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000, 9001)
        .waitingFor(Wait.forListeningPort());

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS, MINIO)).join();
    }

    @DynamicPropertySource
    static void registerWarrantyProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1");
        registry.add("campus.market.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        JourneyDependencyProperties.registerCommonDisabledDependencies(registry);
    }

    static void stopWarrantyContainers() {
        Stream.of(MINIO, REDIS, MYSQL).filter(Objects::nonNull).forEach(GenericContainer::stop);
        NETWORK.close();
    }
}
