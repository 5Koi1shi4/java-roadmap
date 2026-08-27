package com.example.files.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/** Type-safe, startup-validated limits and storage settings for the file service. */
@Validated
@ConfigurationProperties(prefix = "file")
public record FileServiceProperties(
    DataSize maxSize,
    Duration uploadSessionTtl,
    Duration stagingLease,
    Duration stagingWaitTimeout,
    Duration stagingPollInterval,
    Cleanup cleanup,
    Download download,
    Identity identity,
    Storage storage,
    Maintenance maintenance) {

    public static final DataSize SECURITY_MAX_SIZE = DataSize.ofMegabytes(20);
    public static final Duration SECURITY_MAX_LINK_TTL = Duration.ofMinutes(2);

    /** Compatibility constructor for callers that only need the original core settings. */
    public FileServiceProperties(DataSize maxSize, Duration uploadSessionTtl, Duration stagingLease,
                                 Cleanup cleanup, Download download, Identity identity, Storage storage) {
        this(maxSize, uploadSessionTtl, stagingLease, Duration.ofSeconds(5), Duration.ofMillis(100),
            cleanup, download, identity, storage, new Maintenance(false));
    }

    @ConstructorBinding
    public FileServiceProperties {
        require(maxSize, "maxSize");
        require(uploadSessionTtl, "uploadSessionTtl");
        require(stagingLease, "stagingLease");
        require(stagingWaitTimeout, "stagingWaitTimeout");
        require(stagingPollInterval, "stagingPollInterval");
        require(cleanup, "cleanup");
        require(download, "download");
        require(identity, "identity");
        require(storage, "storage");
        require(maintenance, "maintenance");
        if (maxSize.toBytes() <= 0 || maxSize.compareTo(SECURITY_MAX_SIZE) > 0) {
            throw new IllegalArgumentException("maxSize must be between 1 byte and 20 MiB");
        }
        positive(uploadSessionTtl, "uploadSessionTtl");
        positive(stagingLease, "stagingLease");
        positive(stagingWaitTimeout, "stagingWaitTimeout");
        positive(stagingPollInterval, "stagingPollInterval");
        if (stagingPollInterval.compareTo(stagingWaitTimeout) >= 0) {
            throw new IllegalArgumentException("stagingPollInterval must be less than stagingWaitTimeout");
        }
        Duration leaseBudget = stagingWaitTimeout.plus(stagingPollInterval);
        if (stagingLease.compareTo(leaseBudget) <= 0) {
            throw new IllegalArgumentException("stagingLease must exceed the wait timeout and one poll interval");
        }
        Duration sessionBudget = stagingWaitTimeout.plus(stagingLease);
        if (uploadSessionTtl.compareTo(sessionBudget) <= 0) {
            throw new IllegalArgumentException("uploadSessionTtl must exceed staging wait and lease budgets");
        }
    }

    public long maxBytes() {
        return maxSize.toBytes();
    }

    public record Cleanup(int batchSize, Duration lease, List<Duration> retryDelays,
                          int maxAttempts, Duration temporaryObjectFallbackAge) {
        public Cleanup {
            require(lease, "cleanup.lease");
            require(retryDelays, "cleanup.retryDelays");
            require(temporaryObjectFallbackAge, "cleanup.temporaryObjectFallbackAge");
            if (batchSize <= 0 || batchSize > 50) {
                throw new IllegalArgumentException("cleanup.batchSize must be between 1 and 50");
            }
            positive(lease, "cleanup.lease");
            if (retryDelays.isEmpty() || retryDelays.stream().anyMatch(delay -> delay == null || !isPositive(delay))) {
                throw new IllegalArgumentException("cleanup.retryDelays must contain positive durations");
            }
            if (maxAttempts <= 0 || maxAttempts > 5) {
                throw new IllegalArgumentException("cleanup.maxAttempts must be between 1 and 5");
            }
            positive(temporaryObjectFallbackAge, "cleanup.temporaryObjectFallbackAge");
            retryDelays = List.copyOf(retryDelays);
        }
    }

    public record Download(Duration maxLinkTtl, String localHmacSecret) {
        public Download {
            require(maxLinkTtl, "download.maxLinkTtl");
            require(localHmacSecret, "download.localHmacSecret");
            positive(maxLinkTtl, "download.maxLinkTtl");
            if (maxLinkTtl.getNano() != 0) {
                throw new IllegalArgumentException("download.maxLinkTtl must be an integer number of seconds");
            }
            if (maxLinkTtl.compareTo(SECURITY_MAX_LINK_TTL) > 0) {
                throw new IllegalArgumentException("download.maxLinkTtl must not exceed 2 minutes");
            }
        }
    }

    public record Identity(boolean trustedHeaderEnabled) { }

    public record Maintenance(boolean enabled) { }

    public record Storage(String type, String localRoot, String minioEndpoint,
                          String minioAccessKey, String minioSecretKey, String minioBucket,
                          String minioRegion,
                          Duration minioConnectTimeout, Duration minioReadTimeout,
                          Duration minioWriteTimeout) {
        /** 保持本地适配器调用方兼容；MinIO 超时采用安全的短默认值。 */
        public Storage(String type, String localRoot, String minioEndpoint,
                       String minioAccessKey, String minioSecretKey, String minioBucket) {
            this(type, localRoot, minioEndpoint, minioAccessKey, minioSecretKey, minioBucket,
                "us-east-1",
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30));
        }

        public Storage {
            require(type, "storage.type");
            require(localRoot, "storage.localRoot");
            require(minioEndpoint, "storage.minioEndpoint");
            require(minioAccessKey, "storage.minioAccessKey");
            require(minioSecretKey, "storage.minioSecretKey");
            require(minioBucket, "storage.minioBucket");
            require(minioRegion, "storage.minioRegion");
            require(minioConnectTimeout, "storage.minioConnectTimeout");
            require(minioReadTimeout, "storage.minioReadTimeout");
            require(minioWriteTimeout, "storage.minioWriteTimeout");
            if (!type.equals("local") && !type.equals("minio")) {
                throw new IllegalArgumentException("storage.type must be local or minio");
            }
            if (localRoot.isBlank() || minioEndpoint.isBlank() || minioBucket.isBlank()) {
                throw new IllegalArgumentException("storage paths, endpoint and bucket must not be blank");
            }
            if (type.equals("minio")) {
                try {
                    java.net.URI endpoint = java.net.URI.create(minioEndpoint);
                    if (!("http".equalsIgnoreCase(endpoint.getScheme())
                        || "https".equalsIgnoreCase(endpoint.getScheme()))
                        || endpoint.getHost() == null || endpoint.getPath().length() > 0) {
                        throw new IllegalArgumentException("storage.minioEndpoint must be an absolute http(s) URL");
                    }
                    if (endpoint.getQuery() != null || endpoint.getFragment() != null || endpoint.getUserInfo() != null) {
                        throw new IllegalArgumentException("storage.minioEndpoint must not contain query or credentials");
                    }
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("storage.minioEndpoint must be an absolute http(s) URL", ex);
                }
                if (minioAccessKey.isBlank() || minioSecretKey.isBlank()) {
                    throw new IllegalArgumentException("MinIO credentials must not be blank");
                }
                if (!minioBucket.matches("[a-z0-9][a-z0-9.-]{2,62}")) {
                    throw new IllegalArgumentException("storage.minioBucket has invalid format");
                }
                if (!minioRegion.matches("[a-z0-9-]{1,32}")) {
                    throw new IllegalArgumentException("storage.minioRegion has invalid format");
                }
            }
            positive(minioConnectTimeout, "storage.minioConnectTimeout");
            positive(minioReadTimeout, "storage.minioReadTimeout");
            positive(minioWriteTimeout, "storage.minioWriteTimeout");
        }

        /** Secret 不得随 record 默认 toString 泄露到日志。 */
        @Override
        public String toString() {
            return "Storage[type=" + type + ", localRoot=" + localRoot
                + ", minioEndpoint=" + minioEndpoint + ", minioAccessKey=" + minioAccessKey
                + ", minioSecretKey=<redacted>, minioBucket=" + minioBucket
                + ", minioRegion=" + minioRegion
                + ", minioConnectTimeout=" + minioConnectTimeout
                + ", minioReadTimeout=" + minioReadTimeout
                + ", minioWriteTimeout=" + minioWriteTimeout + "]";
        }
    }

    private static void positive(Duration value, String name) {
        if (!isPositive(value)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static boolean isPositive(Duration value) {
        return !value.isZero() && !value.isNegative();
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
