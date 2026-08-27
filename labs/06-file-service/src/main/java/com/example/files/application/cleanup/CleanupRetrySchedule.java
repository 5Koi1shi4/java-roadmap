package com.example.files.application.cleanup;

import java.time.Duration;
import java.util.List;

/** 固定、可审计的失败退避计划；第 5 次失败不再重排。 */
public final class CleanupRetrySchedule {
    private static final List<Duration> DEFAULT = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
        Duration.ofMinutes(2), Duration.ofMinutes(10));
    private final List<Duration> delays;
    private final int maxAttempts;

    public CleanupRetrySchedule() { this(DEFAULT, 5); }
    public CleanupRetrySchedule(List<Duration> delays, int maxAttempts) {
        if (delays == null || delays.isEmpty() || maxAttempts <= 0 || maxAttempts > 5
            || delays.stream().anyMatch(d -> d == null || d.isZero() || d.isNegative())) {
            throw new IllegalArgumentException("invalid cleanup retry schedule");
        }
        this.delays = List.copyOf(delays);
        this.maxAttempts = maxAttempts;
    }
    public boolean shouldRetry(int attempt) { return attempt < maxAttempts; }
    public Duration delayAfter(int attempt) {
        if (attempt <= 0) throw new IllegalArgumentException("attempt must be positive");
        return delays.get(Math.min(attempt - 1, delays.size() - 1));
    }
    public Duration delayForAttempt(int attempt) { return delayAfter(attempt); }
    public boolean isFinalAttempt(int attempt) { return attempt >= maxAttempts; }
    public int maxAttempts() { return maxAttempts; }
    public List<Duration> delays() { return delays; }
}
