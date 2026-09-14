package com.example.campusmarket.gateway.support;

import com.example.campusmarket.discovery.DiscoveryServerApplication;
import com.example.campusmarket.identity.IdentityServiceApplication;
import com.example.campusmarket.identity.infrastructure.LocalVerificationMailSender;
import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.EurekaServerContext;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 真实 Gateway HTTP 旅程使用的四个独立 Spring 应用。
 *
 * <p>每个应用显式加载自己的生产 application.yml，再通过测试属性源只覆盖动态依赖、端口
 * 和测试调度开关。这样 Gateway 测试类路径中的其他应用配置不会串入当前应用，旅程中的
 * 每个请求仍然通过 Gateway 的真实负载均衡路由进入目标服务。</p>
 */
public final class CloudApplicationCluster implements AutoCloseable {
    private static final String REDIS_IMAGE = "redis:7.4.2-alpine";
    private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
        .withExposedPorts(6379);
    private final Path temporaryDirectory;
    private final Path identityMigrations;
    private final Path legacyMigrations;
    private final Path keyDirectory;
    private final Path discoveryConfiguration;
    private final Path identityConfiguration;
    private final Path legacyConfiguration;
    private final Path gatewayConfiguration;
    private final URI discoveryBaseUri;
    private final int discoveryPort;
    private final int identityPort;
    private final int legacyPort;
    private final int gatewayPort;

    private ConfigurableApplicationContext discovery;
    private ConfigurableApplicationContext identity;
    private ConfigurableApplicationContext legacy;
    private ConfigurableApplicationContext gateway;
    private boolean closed;

    private CloudApplicationCluster() {
        try {
            temporaryDirectory = Files.createTempDirectory("cloud-journey-");
            identityMigrations = temporaryDirectory.resolve("identity-migrations");
            legacyMigrations = temporaryDirectory.resolve("legacy-migrations");
            keyDirectory = temporaryDirectory.resolve("jwt");
            TestRsaKeys.writePemPair(keyDirectory);
            Path moduleRoot = moduleRoot();
            discoveryConfiguration = applicationConfiguration(moduleRoot, "discovery-server");
            identityConfiguration = applicationConfiguration(moduleRoot, "identity-service");
            legacyConfiguration = applicationConfiguration(moduleRoot, "legacy-market-service");
            gatewayConfiguration = applicationConfiguration(moduleRoot, "api-gateway");
            stageMigrations(moduleRoot, "identity-service", identityMigrations);
            stageMigrations(moduleRoot, "legacy-market-service", legacyMigrations);
            discoveryPort = randomPort();
            identityPort = randomPort();
            legacyPort = randomPort();
            gatewayPort = randomPort();
            discoveryBaseUri = URI.create("http://127.0.0.1:" + discoveryPort + "/eureka/");
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("无法准备 Cloud 旅程临时目录", exception);
        }
    }

    /** 启动 MySQL、Redis 和四个独立的应用上下文。 */
    public static CloudApplicationCluster start() {
        CloudApplicationCluster cluster = new CloudApplicationCluster();
        try {
            cluster.launch();
            return cluster;
        } catch (RuntimeException | Error failure) {
            try {
                cluster.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public URI gatewayBaseUri() {
        return URI.create("http://127.0.0.1:" + gatewayPort + "/");
    }

    /** 只关闭身份服务上下文，供失败路径测试检查其他上下文。 */
    public void stopIdentity() {
        identity = closeContext(identity);
    }

    /** 只关闭市场服务上下文，供失败路径测试检查其他上下文。 */
    public void stopLegacy() {
        legacy = closeContext(legacy);
    }

    /** 先停止客户端后关闭 Eureka 上下文。 */
    public void stopDiscovery() {
        discovery = closeContext(discovery);
    }

    /** 返回最新的本地夹具验证码，不把验证码放到 HTTP 或日志边界。 */
    public String latestVerificationCode(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        return requireContext(identity).getBean(LocalVerificationMailSender.class).latestCode(email);
    }

    /** Gateway 写入后从市场服务真实数据库读取卖家 ID。 */
    public UUID listingSellerId(UUID listingId) {
        Objects.requireNonNull(listingId, "商品 ID 不能为空");
        String sellerId = requireContext(legacy).getBean(JdbcTemplate.class)
            .queryForObject("SELECT seller_id FROM listing WHERE id = ?", String.class, listingId.toString());
        return UUID.fromString(sellerId);
    }

    /** 返回真实 Eureka 注册表当前可见的应用。 */
    public Set<String> registryApplications() {
        EurekaServerContext server = requireContext(discovery).getBean(EurekaServerContext.class);
        return server.getRegistry().getSortedApplications().stream()
            .map(Application::getName)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** 返回 Eureka 报告的注册端口，按应用名称分组。 */
    public Map<String, List<Integer>> registryInstancePorts() {
        EurekaServerContext server = requireContext(discovery).getBean(EurekaServerContext.class);
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (Application application : server.getRegistry().getSortedApplications()) {
            result.put(application.getName(), application.getInstances().stream()
                .map(InstanceInfo::getPort).toList());
        }
        return Collections.unmodifiableMap(result);
    }

    /** 返回 Gateway 路由定义，包括真实 Eureka 负载均衡目标。 */
    public Map<String, String> gatewayRouteUris() {
        List<org.springframework.cloud.gateway.route.RouteDefinition> routes =
            requireContext(gateway).getBean(RouteDefinitionLocator.class)
                .getRouteDefinitions().collectList().block(Duration.ofSeconds(10));
        if (routes == null) {
            throw new IllegalStateException("Gateway 路由定义未加载");
        }
        Map<String, String> result = routes.stream().collect(Collectors.toMap(
            org.springframework.cloud.gateway.route.RouteDefinition::getId,
            route -> route.getUri().toString(),
            (left, right) -> left,
            LinkedHashMap::new));
        return Collections.unmodifiableMap(result);
    }

    /** 返回三个注册客户端实际 WebServer 端口，供注册表端口精确比对。 */
    public Map<String, Integer> webServerPorts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("IDENTITY-SERVICE", webServerPort(identity));
        result.put("LEGACY-MARKET-SERVICE", webServerPort(legacy));
        result.put("API-GATEWAY", webServerPort(gateway));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Throwable cleanupFailure = null;
        ConfigurableApplicationContext gatewayContext = gateway;
        gateway = null;
        cleanupFailure = appendFailure(cleanupFailure, closeContextSafely(gatewayContext));
        ConfigurableApplicationContext legacyContext = legacy;
        legacy = null;
        cleanupFailure = appendFailure(cleanupFailure, closeContextSafely(legacyContext));
        ConfigurableApplicationContext identityContext = identity;
        identity = null;
        cleanupFailure = appendFailure(cleanupFailure, closeContextSafely(identityContext));
        ConfigurableApplicationContext discoveryContext = discovery;
        discovery = null;
        cleanupFailure = appendFailure(cleanupFailure, closeContextSafely(discoveryContext));
        try {
            if (redis.isRunning()) {
                redis.stop();
            }
        } catch (RuntimeException | Error failure) {
            cleanupFailure = appendFailure(cleanupFailure, failure);
        }
        cleanupFailure = appendFailure(cleanupFailure, deleteRecursively(temporaryDirectory));
        throwIfCleanupFailed(cleanupFailure);
    }

    private void launch() {
        Startables.deepStart(Stream.of(redis)).join();
        String redisUrl = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        String zone = discoveryBaseUri.toString();

        discovery = applicationBuilder(DiscoveryServerApplication.class, WebApplicationType.SERVLET,
            discoveryConfiguration, discoveryProperties())
            .run();
        assertPort(discovery, discoveryPort, "discovery-server");

        identity = new SpringApplicationBuilder(IdentityServiceApplication.class)
            .web(WebApplicationType.SERVLET)
            .properties(configLocation(identityConfiguration))
            .profiles("local")
            .initializers(highPriorityProperties(identityProperties(zone, redisUrl)))
            .run();
        assertPort(identity, identityPort, "identity-service");
        awaitRegistry(Set.of("IDENTITY-SERVICE"));

        String identityJwks = "http://127.0.0.1:" + identityPort + "/api/auth/.well-known/jwks.json";
        legacy = new SpringApplicationBuilder(LegacyMarketApplication.class)
            .web(WebApplicationType.SERVLET)
            .properties(configLocation(legacyConfiguration))
            .profiles("local")
            .initializers(highPriorityProperties(legacyProperties(zone, redisUrl, identityJwks)))
            .run();
        assertPort(legacy, legacyPort, "legacy-market-service");
        awaitRegistry(Set.of("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE"));

        gateway = new SpringApplicationBuilder(com.example.campusmarket.gateway.ApiGatewayApplication.class)
            .web(WebApplicationType.REACTIVE)
            .properties(configLocation(gatewayConfiguration))
            .initializers(highPriorityProperties(gatewayProperties(zone, identityJwks)))
            .run();
        assertPort(gateway, gatewayPort, "api-gateway");
        awaitRegistry(Set.of("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE", "API-GATEWAY"));
        awaitGatewayDiscovery();
    }

    private Map<String, Object> discoveryProperties() {
        Map<String, Object> properties = commonProperties(discoveryPort, null);
        properties.put("eureka.server.enable-self-preservation", false);
        properties.put("eureka.server.response-cache-update-interval-ms", 100);
        properties.put("eureka.server.eviction-interval-timer-in-ms", 1_000);
        return properties;
    }

    private Map<String, Object> identityProperties(String zone, String redisUrl) {
        Properties database = SplitDatabaseContainer.identityProperties();
        Map<String, Object> properties = commonProperties(identityPort, zone);
        properties.put("spring.data.redis.url", redisUrl);
        properties.put("spring.data.redis.repositories.enabled", false);
        properties.put("spring.data.redis.connect-timeout", "2s");
        properties.put("spring.data.redis.timeout", "2s");
        database.forEach((key, value) -> {
            if (key.toString().startsWith("spring.datasource.") || key.toString().startsWith("spring.flyway.")) {
                properties.put(key.toString(), value);
            }
        });
        properties.put("spring.flyway.locations", filesystemLocation(identityMigrations));
        properties.put("campus.market.jwt.private-key", keyDirectory.resolve("test-private-key.pem").toUri().toString());
        properties.put("campus.market.jwt.public-key", keyDirectory.resolve("test-public-key.pem").toUri().toString());
        properties.put("spring.task.scheduling.enabled", false);
        return properties;
    }

    private Map<String, Object> legacyProperties(String zone, String redisUrl, String identityJwks) {
        Properties database = SplitDatabaseContainer.marketProperties();
        Map<String, Object> properties = commonProperties(legacyPort, zone);
        properties.put("spring.data.redis.url", redisUrl);
        properties.put("spring.data.redis.repositories.enabled", false);
        database.forEach((key, value) -> {
            if (key.toString().startsWith("spring.datasource.") || key.toString().startsWith("spring.flyway.")) {
                properties.put(key.toString(), value);
            }
        });
        properties.put("spring.flyway.locations", filesystemLocation(legacyMigrations));
        properties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", identityJwks);
        properties.put("spring.rabbitmq.host", "127.0.0.1");
        properties.put("spring.rabbitmq.port", 1);
        properties.put("spring.rabbitmq.dynamic", false);
        properties.put("spring.rabbitmq.listener.simple.auto-startup", false);
        properties.put("spring.rabbitmq.listener.direct.auto-startup", false);
        properties.put("spring.rabbitmq.connection-timeout", "100ms");
        properties.put("spring.elasticsearch.uris", "http://127.0.0.1:1");
        properties.put("spring.elasticsearch.connection-timeout", "100ms");
        properties.put("spring.elasticsearch.socket-timeout", "100ms");
        properties.put("campus.market.storage.endpoint", "http://127.0.0.1:1");
        properties.put("campus.market.search.dispatcher.enabled", false);
        properties.put("campus.market.order.deadline.enabled", false);
        properties.put("campus.market.payment.reconciliation.enabled", false);
        properties.put("campus.market.dispute.deadline.enabled", false);
        properties.put("campus.market.dispute.return-reconciliation.enabled", false);
        properties.put("campus.market.warranty.deadline.enabled", false);
        properties.put("spring.task.scheduling.enabled", false);
        return properties;
    }

    private Map<String, Object> gatewayProperties(String zone, String identityJwks) {
        Map<String, Object> properties = commonProperties(gatewayPort, zone);
        properties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", identityJwks);
        return properties;
    }

    private Map<String, Object> commonProperties(int port, String zone) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.register-shutdown-hook", false);
        properties.put("server.port", port);
        properties.put("server.shutdown", "graceful");
        if (zone != null) {
            properties.put("eureka.client.service-url.defaultZone", zone);
            properties.put("eureka.client.register-with-eureka", true);
            properties.put("eureka.client.fetch-registry", true);
            properties.put("eureka.client.initial-instance-info-replication-interval-seconds", 1);
            properties.put("eureka.client.instance-info-replication-interval-seconds", 1);
            properties.put("eureka.client.registry-fetch-interval-seconds", 1);
            properties.put("eureka.instance.hostname", "127.0.0.1");
            properties.put("eureka.instance.ip-address", "127.0.0.1");
            properties.put("eureka.instance.prefer-ip-address", true);
            properties.put("eureka.instance.non-secure-port", port);
            properties.put("eureka.instance.secure-port-enabled", false);
            properties.put("eureka.instance.lease-renewal-interval-in-seconds", 1);
            properties.put("eureka.instance.lease-expiration-duration-in-seconds", 5);
            properties.put("eureka.instance.status-page-url", "http://127.0.0.1:" + port + "/actuator/info");
            properties.put("eureka.instance.health-check-url", "http://127.0.0.1:" + port + "/actuator/health");
        }
        return properties;
    }

    private void awaitRegistry(Set<String> expected) {
        long deadline = System.nanoTime() + REGISTRY_TIMEOUT.toNanos();
        Set<String> normalized = expected.stream().map(String::toUpperCase).collect(Collectors.toSet());
        while (System.nanoTime() < deadline) {
            if (registryApplications().containsAll(normalized)) return;
            pause();
        }
        throw new IllegalStateException("Eureka 注册超时；期望=" + normalized + "，实际=" + registryApplications());
    }

    private void awaitGatewayDiscovery() {
        DiscoveryClient client = requireContext(gateway).getBean(DiscoveryClient.class);
        long deadline = System.nanoTime() + REGISTRY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!client.getInstances("identity-service").isEmpty()
                && !client.getInstances("legacy-market-service").isEmpty()) return;
            pause();
        }
        throw new IllegalStateException("Gateway 未发现身份或市场服务");
    }

    private void assertPort(ConfigurableApplicationContext context, int expected, String applicationName) {
        int actual = ((WebServerApplicationContext) context).getWebServer().getPort();
        if (actual != expected) {
            throw new IllegalStateException(applicationName + " 端口不匹配：期望 " + expected + "，实际 " + actual);
        }
    }

    private static int webServerPort(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) requireContext(context)).getWebServer().getPort();
    }

    private static ConfigurableApplicationContext closeContext(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
        return null;
    }

    private static ConfigurableApplicationContext requireContext(ConfigurableApplicationContext context) {
        return Objects.requireNonNull(context, "应用上下文尚未启动");
    }

    private static SpringApplicationBuilder applicationBuilder(Class<?> source, WebApplicationType type,
                                                               Path configuration, Map<String, Object> overrides) {
        return new SpringApplicationBuilder(source)
            .web(type)
            .properties(configLocation(configuration))
            .initializers(highPriorityProperties(overrides));
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> highPriorityProperties(
        Map<String, Object> properties) {
        Map<String, Object> copy = new LinkedHashMap<>(properties);
        return context -> context.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("cloud-journey-overrides", copy));
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String filesystemLocation(Path path) {
        return "filesystem:" + path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String configLocation(Path path) {
        return "spring.config.location=" + path.toAbsolutePath().toUri();
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Eureka 时被中断", interrupted);
        }
    }

    private static void stageMigrations(Path moduleRoot, String module, Path target)
        throws IOException {
        Path source = moduleRoot.resolveSibling(module).resolve("src/main/resources/db/migration");
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("缺少 " + module + " 数据库迁移目录：" + source);
        }
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(source)) {
            files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted().forEach(path -> copyMigration(path, target));
        }
    }

    private static Path moduleRoot() throws URISyntaxException {
        Path location = Path.of(CloudApplicationCluster.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toAbsolutePath();
        if (Files.isRegularFile(location)) location = location.getParent();
        for (Path candidate = location; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) return candidate;
        }
        throw new IllegalStateException("无法定位 spring-cloud-split 模块根目录：" + location);
    }

    private static void copyMigration(Path source, Path target) {
        try {
            Files.copy(source, target.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("无法复制数据库迁移：" + source, exception);
        }
    }

    private static Path applicationConfiguration(Path moduleRoot, String module) {
        Path path = moduleRoot.resolveSibling(module).resolve("src/main/resources/application.yml");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("缺少 " + module + " 的真实 application.yml：" + path);
        }
        return path;
    }

    private static Throwable closeContextSafely(ConfigurableApplicationContext context) {
        if (context == null) return null;
        try {
            context.close();
            return null;
        } catch (RuntimeException | Error failure) {
            return failure;
        }
    }

    private static Throwable appendFailure(Throwable current, Throwable next) {
        if (next == null) return current;
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private static void throwIfCleanupFailed(Throwable cleanupFailure) {
        if (cleanupFailure instanceof RuntimeException failure) throw failure;
        if (cleanupFailure instanceof Error failure) throw failure;
    }

    private static Throwable deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return null;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }
}
