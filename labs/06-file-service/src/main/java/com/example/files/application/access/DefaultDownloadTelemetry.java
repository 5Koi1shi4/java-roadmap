package com.example.files.application.access;

import com.example.files.application.audit.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 默认失败计数和安全结构化日志实现，不记录身份凭证、key 或路径。 */
public final class DefaultDownloadTelemetry implements DownloadTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultDownloadTelemetry.class);
    private final AtomicLong failures = new AtomicLong();
    private final MeterRegistry registry;

    public DefaultDownloadTelemetry() { this(null); }
    public DefaultDownloadTelemetry(MeterRegistry registry) { this.registry = registry; }

    @Override
    public void failed(UUID fileId, CorrelationId correlationId, String failureCode) {
        failures.incrementAndGet();
        DownloadFailureReason reason = DownloadFailureReason.fromCode(failureCode);
        if (registry != null) {
            Counter.builder("file.download.failures").tag("reason", reason.name()).register(registry).increment();
        }
        LOG.warn("download failure correlationId={} fileId={} failureCode={}",
            correlationId == null ? null : correlationId.value(), fileId, reason.name());
    }

    public long failureCount() { return failures.get(); }
}
