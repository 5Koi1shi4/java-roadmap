package com.example.files.application.upload;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** 对 STAGING 领取结果进行短查询、有界等待；每次解析都在调用方短事务中完成。 */
public final class StagingWaitPolicy {
    private final Duration timeout;
    private final Duration interval;

    public StagingWaitPolicy() {
        this(Duration.ofSeconds(5), Duration.ofMillis(100));
    }

    public StagingWaitPolicy(Duration timeout, Duration interval) {
        if (timeout == null || interval == null || timeout.isZero() || timeout.isNegative()
            || interval.isZero() || interval.isNegative() || interval.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("invalid staging wait policy");
        }
        this.timeout = timeout;
        this.interval = interval;
    }

    public StagingWaitPolicy(com.example.files.config.FileServiceProperties properties) {
        this(Objects.requireNonNull(properties, "properties").stagingWaitTimeout(),
            properties.stagingPollInterval());
    }

    /** 等待期间只保留不可携带能力的 Waiting；获得 Granted 后立即返回。 */
    public BlobReservation.Granted awaitOwnershipOrReady(BlobReservation initial,
                                                           Supplier<BlobReservation> resolver) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(resolver, "resolver");
        if (initial instanceof BlobReservation.Granted granted) return granted;
        long deadline = System.nanoTime() + timeout.toNanos();
        BlobReservation current = initial;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(Math.max(1L, interval.toMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new StorageCoordinationUnavailableException("staging wait interrupted", interrupted);
            }
            current = resolver.get();
            if (current instanceof BlobReservation.Granted granted) return granted;
        }
        throw new StorageCoordinationUnavailableException("staging coordination timed out");
    }

    public Duration timeout() { return timeout; }
    public Duration interval() { return interval; }
}
