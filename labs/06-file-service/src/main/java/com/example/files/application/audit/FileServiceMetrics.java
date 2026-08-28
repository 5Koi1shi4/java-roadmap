package com.example.files.application.audit;

import java.time.Duration;

/** 文件服务观测端口；实现必须只接受代码内固定的低基数枚举值。 */
public interface FileServiceMetrics {
    void recordUpload(String result, Duration duration);
    void recordSession(String status);
    void setSessionCount(String status, long count);
    void recordBlob(String status);
    void setBlobCount(String status, long count);
    void setStagingOldest(Duration age);
    void recordCleanupPending(String type);
    void setCleanupPending(String type, long count);
    void recordCleanupRetry(String type, String result);
    void recordDownload(String phase, String result);
    void recordAcl(String action, String result);
    void recordStorageOperation(String operation, String result, Duration duration);
}
