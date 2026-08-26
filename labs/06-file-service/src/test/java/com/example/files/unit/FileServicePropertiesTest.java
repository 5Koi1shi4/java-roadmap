package com.example.files.unit;

import com.example.files.config.FileServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServicePropertiesTest {

    @Test
    void rejectsConfigurationThatWeakensSecurityBounds() {
        assertThatThrownBy(() -> propertiesWithMaxSize(DataSize.ofMegabytes(21)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static FileServiceProperties propertiesWithMaxSize(DataSize maxSize) {
        return new FileServiceProperties(
            maxSize,
            Duration.ofHours(1),
            Duration.ofMinutes(2),
            new FileServiceProperties.Cleanup(
                50,
                Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)),
                5,
                Duration.ofHours(24)),
            new FileServiceProperties.Download(Duration.ofMinutes(2), ""),
            new FileServiceProperties.Identity(false),
            new FileServiceProperties.Storage("local", "./data/files", "http://localhost:9000", "", "", "secure-files"));
    }
}
