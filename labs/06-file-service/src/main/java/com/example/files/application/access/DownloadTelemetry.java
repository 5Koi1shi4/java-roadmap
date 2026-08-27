package com.example.files.application.access;

import com.example.files.application.audit.CorrelationId;

import java.util.UUID;

/** 下载失败的低基数、不可失败观测端口。 */
@FunctionalInterface
public interface DownloadTelemetry {
    void failed(UUID fileId, CorrelationId correlationId, String failureCode);
}
