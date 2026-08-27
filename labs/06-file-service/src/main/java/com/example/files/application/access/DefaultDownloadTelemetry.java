package com.example.files.application.access;

import com.example.files.application.audit.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 默认失败计数和安全结构化日志实现，不记录身份凭证、key 或路径。 */
public final class DefaultDownloadTelemetry implements DownloadTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultDownloadTelemetry.class);
    private final AtomicLong failures = new AtomicLong();

    @Override
    public void failed(UUID fileId, CorrelationId correlationId, String failureCode) {
        failures.incrementAndGet();
        LOG.warn("download failure correlationId={} fileId={} failureCode={}",
            correlationId == null ? null : correlationId.value(), fileId, failureCode);
    }

    public long failureCount() { return failures.get(); }
}
