package com.example.files.integration;

import com.example.files.config.FileServiceProperties;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.lifecycle.Startable;

import java.time.Duration;
import java.util.List;

/** 三个真实服务共享一个隔离网络；应用端的 MinIO 地址始终指向 Toxiproxy。 */
public abstract class SharedStorageContainers {
    static final String BUCKET = "secure-files";
    static final String ACCESS_KEY = "minioadmin";
    static final String SECRET_KEY = "minioadmin-local";
    private static final Network NETWORK = Network.newNetwork();
    static final MySQLContainer<?> MYSQL;
    static final GenericContainer<?> MINIO;
    static final ToxiproxyContainer TOXIPROXY;
    static final ToxiproxyContainer.ContainerProxy MINIO_PROXY;
    private static final MinioClient MINIO_CLIENT;

    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("secure_files")
            .withUsername("secure_files")
            .withPassword("secure_files_local")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql");
        MYSQL.start();

        MINIO = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-10-15T17-29-55Z"))
            .withCommand("server /data --console-address :9001")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withNetwork(NETWORK)
            .withNetworkAliases("minio")
            .withExposedPorts(9000, 9001)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        MINIO.start();

        TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK)
            .withNetworkAliases("toxiproxy")
            .waitingFor(Wait.forListeningPort());
        TOXIPROXY.start();
        MINIO_PROXY = TOXIPROXY.getProxy(MINIO, 9000);
        MINIO_CLIENT = buildMinioClient();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopQuietly(TOXIPROXY), "storage-toxiproxy-shutdown"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopQuietly(MINIO), "storage-minio-shutdown"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopQuietly(MYSQL), "storage-mysql-shutdown"));
    }

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("file.storage.type", () -> "minio");
        registry.add("file.storage.minio-endpoint", SharedStorageContainers::minioEndpoint);
        registry.add("file.storage.minio-access-key", () -> ACCESS_KEY);
        registry.add("file.storage.minio-secret-key", () -> SECRET_KEY);
        registry.add("file.storage.minio-bucket", () -> BUCKET);
        registry.add("file.storage.minio-region", () -> "us-east-1");
    }

    static String minioEndpoint() {
        return "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(MINIO_PROXY.getProxyPort());
    }

    static FileServiceProperties.Storage minioStorage() {
        return new FileServiceProperties.Storage("minio", "target/minio-it-storage", minioEndpoint(),
            ACCESS_KEY, SECRET_KEY, BUCKET);
    }

    static void cutMinioConnection(boolean cut) {
        MINIO_PROXY.setConnectionCut(cut);
    }

    static void denyTemporaryDeletes() {
        String policy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":{"AWS":["*"]},"Action":["s3:DeleteObject"],"Resource":["arn:aws:s3:::secure-files/tmp/*"]}]}
            """;
        try {
            MINIO_CLIENT.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(BUCKET).config(policy).build());
        } catch (Exception ex) {
            throw new IllegalStateException("cannot install temporary-object deny policy", ex);
        }
    }

    static void allowTemporaryDeletes() {
        try {
            MINIO_CLIENT.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(BUCKET).build());
        } catch (Exception ex) {
            throw new IllegalStateException("cannot clear temporary-object deny policy", ex);
        }
    }

    static MinioClient minioClient() { return MINIO_CLIENT; }

    private static MinioClient buildMinioClient() {
        try {
            Object builder = MinioClient.class.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("endpoint", String.class).invoke(builder, minioEndpoint());
            builder = builder.getClass().getMethod("credentials", String.class, String.class)
                .invoke(builder, ACCESS_KEY, SECRET_KEY);
            return (MinioClient) builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot create MinIO test client", ex);
        }
    }

    private static void stopQuietly(Startable container) {
        try { container.stop(); } catch (RuntimeException ignored) { }
    }
}
