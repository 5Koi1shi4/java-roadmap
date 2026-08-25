package com.example.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("search")
public record SearchProperties(int batchSize, Duration leaseDuration, Duration requestTimeout,
                               Duration dispatchDelay, Maintenance maintenance) {
    public SearchProperties() {
        this(50, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1),
                new Maintenance(false));
    }

    public SearchProperties {
        if (batchSize < 1 || batchSize > 50) {
            throw new IllegalArgumentException("batch size must be between 1 and 50");
        }
        leaseDuration = positive(leaseDuration, "lease duration");
        requestTimeout = positive(requestTimeout, "request timeout");
        dispatchDelay = positive(dispatchDelay, "dispatch delay");
        if (leaseDuration.compareTo(requestTimeout) <= 0) {
            throw new IllegalArgumentException("lease duration must be greater than request timeout");
        }
        maintenance = Objects.requireNonNull(maintenance, "maintenance is required");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public record Maintenance(boolean enabled) { }
}
