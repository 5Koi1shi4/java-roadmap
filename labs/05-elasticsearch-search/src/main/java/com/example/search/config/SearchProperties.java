package com.example.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("search")
public record SearchProperties(@DefaultValue("50") int batchSize,
                               @DefaultValue("30s") Duration leaseDuration,
                               @DefaultValue("10s") Duration requestTimeout,
                               @DefaultValue("1s") Duration dispatchDelay,
                               @DefaultValue Maintenance maintenance) {

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

    public record Maintenance(@DefaultValue("false") boolean enabled) { }
}
