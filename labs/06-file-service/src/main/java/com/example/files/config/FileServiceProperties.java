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
            if (maxLinkTtl.compareTo(SECURITY_MAX_LINK_TTL) > 0) {
                throw new IllegalArgumentException("download.maxLinkTtl must not exceed 2 minutes");
            }
        }
    }

    public record Identity(boolean trustedHeaderEnabled) { }

    public record Maintenance(boolean enabled) { }

    public record Storage(String type, String localRoot, String minioEndpoint,
                          String minioAccessKey, String minioSecretKey, String minioBucket) {
        public Storage {
            require(type, "storage.type");
            require(localRoot, "storage.localRoot");
            require(minioEndpoint, "storage.minioEndpoint");
            require(minioAccessKey, "storage.minioAccessKey");
            require(minioSecretKey, "storage.minioSecretKey");
            require(minioBucket, "storage.minioBucket");
            if (!type.equals("local") && !type.equals("minio")) {
                throw new IllegalArgumentException("storage.type must be local or minio");
            }
            if (localRoot.isBlank() || minioEndpoint.isBlank() || minioBucket.isBlank()) {
                throw new IllegalArgumentException("storage paths, endpoint and bucket must not be blank");
            }
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
