package com.example.files.unit;

import com.example.files.config.FileServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class FileServicePropertiesTest {

    @Test
    void rejectsConfigurationThatWeakensSecurityBounds() {
        assertThatThrownBy(() -> propertiesWithMaxSize(DataSize.ofMegabytes(21)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidStagingPollingConfiguration() {
        assertThatThrownBy(() -> propertiesWithStaging(Duration.ZERO, Duration.ofMillis(100)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propertiesWithStaging(Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propertiesWithLease(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(100)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propertiesWithLease(Duration.ofSeconds(5), Duration.ofSeconds(11), Duration.ofMillis(100)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksMinioSecretInConfigurationToString() {
        FileServiceProperties.Storage storage = new FileServiceProperties.Storage(
            "minio", "./data/files", "http://localhost:9000", "access", "super-secret", "secure-files");
        assertThat(storage.toString()).doesNotContain("super-secret").contains("<redacted>");
    }

    @Test
    void legacyFixtureWithNullStorageUsesTypeSafeLocalDefaults() {
        FileServiceProperties properties = new FileServiceProperties(
            DataSize.ofMegabytes(20),
            Duration.ofHours(1),
            Duration.ofMinutes(2),
            new FileServiceProperties.Cleanup(50, Duration.ofSeconds(30), List.of(Duration.ofSeconds(5)), 5,
                Duration.ofHours(24)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(false),
            null);

        assertThat(properties.storage()).isNotNull();
        assertThat(properties.storage().type()).isEqualTo("local");
        assertThat(properties.storage().localRoot()).isNotBlank();
    }

    private static FileServiceProperties propertiesWithMaxSize(DataSize maxSize) {
        return new FileServiceProperties(
            maxSize,
            Duration.ofHours(1),
            Duration.ofMinutes(2),
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            new FileServiceProperties.Cleanup(
                50,
                Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)),
                5,
                Duration.ofHours(24)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(false),
            new FileServiceProperties.Storage("local", "./data/files", "http://localhost:9000", "", "", "secure-files"),
            new FileServiceProperties.Maintenance(false));
    }

    private static FileServiceProperties propertiesWithStaging(Duration wait, Duration poll) {
        return new FileServiceProperties(
            DataSize.ofMegabytes(20), Duration.ofHours(1), Duration.ofMinutes(2), wait, poll,
            new FileServiceProperties.Cleanup(50, Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(5)), 5, Duration.ofHours(24)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(false),
            new FileServiceProperties.Storage("local", "./data/files", "http://localhost:9000", "", "", "secure-files"),
            new FileServiceProperties.Maintenance(false));
    }

    private static FileServiceProperties propertiesWithLease(Duration ttl, Duration lease, Duration poll) {
        return new FileServiceProperties(
            DataSize.ofMegabytes(20), ttl, lease, Duration.ofSeconds(5), poll,
            new FileServiceProperties.Cleanup(50, Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(5)), 5, Duration.ofHours(24)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(false),
            new FileServiceProperties.Storage("local", "./data/files", "http://localhost:9000", "", "", "secure-files"),
            new FileServiceProperties.Maintenance(false));
    }
}
