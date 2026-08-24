package com.example.search.application.sync;

import java.time.Duration;
import java.util.Optional;

/** Fixed retry buckets for search outbox delivery. */
public final class RetrySchedule {
    public static Optional<Duration> delayAfterFailure(int attempt) {
        return switch (attempt) {
            case 1 -> Optional.of(Duration.ofSeconds(1));
            case 2 -> Optional.of(Duration.ofSeconds(5));
            case 3 -> Optional.of(Duration.ofSeconds(30));
            case 4 -> Optional.of(Duration.ofMinutes(2));
            default -> Optional.empty();
        };
    }
}
