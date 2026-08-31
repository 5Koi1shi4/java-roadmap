package com.example.campusmarket.unit.storage;

import com.example.campusmarket.storage.MinioPrivateObjectStorage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioPrivateObjectStorageTest {
    @Test
    void rejectsNonPositiveTimeoutAtConstruction() {
        assertThatThrownBy(() -> storage(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTimeoutAboveConfiguredMaximumAtConstruction() {
        assertThatThrownBy(() -> storage(Duration.ofSeconds(11), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private MinioPrivateObjectStorage storage(Duration connect, Duration read, Duration write, Duration call) {
        return new MinioPrivateObjectStorage("http://localhost:9000", "key", "secret", "bucket",
            connect, read, write, call);
    }
}
