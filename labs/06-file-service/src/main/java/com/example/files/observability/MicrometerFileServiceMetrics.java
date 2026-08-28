package com.example.files.observability;

import com.example.files.application.audit.FileServiceMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Micrometer 适配器；任何动态文本在注册 meter 前都会被固定 allow-list 拒绝。 */
public final class MicrometerFileServiceMetrics implements FileServiceMetrics {
    private static final Set<String> UPLOAD_RESULTS = Set.of("success", "rejected", "failed");
    private static final Set<String> SESSION_STATUSES = Set.of("RECEIVING", "VALIDATED", "FINALIZING", "COMPLETED", "FAILED", "EXPIRED");
    private static final Set<String> BLOB_STATUSES = Set.of("STAGING", "READY", "PENDING_DELETE", "DELETING", "DELETED");
    private static final Set<String> CLEANUP_TYPES = Set.of("TEMP_OBJECT", "BLOB_OBJECT");
    private static final Set<String> CLEANUP_RESULTS = Set.of("success", "retry", "failed");
    private static final Set<String> DOWNLOAD_PHASES = Set.of("authorization", "transfer", "link");
    private static final Set<String> ACL_ACTIONS = Set.of("grant", "revoke", "delete");
    private static final Set<String> STORAGE_OPERATIONS = Set.of("write_temporary", "commit", "open", "stat", "delete", "presign");
    private static final Set<String> OP_RESULTS = Set.of("success", "retry", "failed");

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> sessionCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> blobCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cleanupPending = new ConcurrentHashMap<>();
    private final AtomicLong oldestStagingSeconds = new AtomicLong();

    public MicrometerFileServiceMetrics(MeterRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        SESSION_STATUSES.forEach(status -> {
            AtomicLong value = sessionCounts.computeIfAbsent(status, ignored -> new AtomicLong());
            registry.gauge("file.session.count", io.micrometer.core.instrument.Tags.of("status", status), value);
        });
        BLOB_STATUSES.forEach(status -> {
            AtomicLong value = blobCounts.computeIfAbsent(status, ignored -> new AtomicLong());
            registry.gauge("file.blob.count", io.micrometer.core.instrument.Tags.of("status", status), value);
        });
        CLEANUP_TYPES.forEach(type -> {
            AtomicLong value = cleanupPending.computeIfAbsent(type, ignored -> new AtomicLong());
            registry.gauge("file.cleanup.pending", io.micrometer.core.instrument.Tags.of("type", type), value);
        });
        registry.gauge("file.staging.oldest.seconds", oldestStagingSeconds);
        // 预注册固定组合，便于 Actuator 在没有请求时也能发现完整指标契约。
        UPLOAD_RESULTS.forEach(result -> Counter.builder("file.upload.total").tag("result", result).register(registry));
        DOWNLOAD_PHASES.forEach(phase -> OP_RESULTS.forEach(result ->
            Counter.builder("file.download.total").tags("phase", phase, "result", result).register(registry)));
        ACL_ACTIONS.forEach(action -> OP_RESULTS.forEach(result ->
            Counter.builder("file.acl.total").tags("action", action, "result", result).register(registry)));
        CLEANUP_TYPES.forEach(type -> CLEANUP_RESULTS.forEach(result ->
            Counter.builder("file.cleanup.retry.total").tags("type", type, "result", result).register(registry)));
        STORAGE_OPERATIONS.forEach(operation -> OP_RESULTS.forEach(result ->
            Timer.builder("file.storage.operation.duration").tags("operation", operation, "result", result).register(registry)));
    }

    @Override public void recordUpload(String result, Duration duration) {
        String value = fixed(result, UPLOAD_RESULTS, "upload result");
        requireDuration(duration, "upload duration");
        Counter.builder("file.upload.total").tag("result", value).register(registry).increment();
        Timer.builder("file.upload.duration").register(registry).record(duration);
    }
    @Override public void recordSession(String status) { sessionCounts.get(fixed(status, SESSION_STATUSES, "session status")).incrementAndGet(); }
    @Override public void setSessionCount(String status, long count) { set(sessionCounts, status, count, SESSION_STATUSES, "session status"); }
    @Override public void recordBlob(String status) { blobCounts.get(fixed(status, BLOB_STATUSES, "blob status")).incrementAndGet(); }
    @Override public void setBlobCount(String status, long count) { set(blobCounts, status, count, BLOB_STATUSES, "blob status"); }
    @Override public void setStagingOldest(Duration age) {
        requireDuration(age, "staging age");
        if (age.isNegative()) throw new IllegalArgumentException("staging age must not be negative");
        oldestStagingSeconds.set(age.getSeconds());
    }
    @Override public void recordCleanupPending(String type) { cleanupPending.get(fixed(type, CLEANUP_TYPES, "cleanup type")).incrementAndGet(); }
    @Override public void setCleanupPending(String type, long count) { set(cleanupPending, type, count, CLEANUP_TYPES, "cleanup type"); }
    @Override public void recordCleanupRetry(String type, String result) {
        Counter.builder("file.cleanup.retry.total").tags("type", fixed(type, CLEANUP_TYPES, "cleanup type"),
            "result", fixed(result, CLEANUP_RESULTS, "cleanup result")).register(registry).increment();
    }
    @Override public void recordDownload(String phase, String result) {
        Counter.builder("file.download.total").tags("phase", fixed(phase, DOWNLOAD_PHASES, "download phase"),
            "result", fixed(result, OP_RESULTS, "download result")).register(registry).increment();
    }
    @Override public void recordAcl(String action, String result) {
        Counter.builder("file.acl.total").tags("action", fixed(action, ACL_ACTIONS, "ACL action"),
            "result", fixed(result, OP_RESULTS, "ACL result")).register(registry).increment();
    }
    @Override public void recordStorageOperation(String operation, String result, Duration duration) {
        requireDuration(duration, "storage duration");
        Timer.builder("file.storage.operation.duration").tags("operation", fixed(operation, STORAGE_OPERATIONS, "storage operation"),
            "result", fixed(result, OP_RESULTS, "storage result")).register(registry).record(duration);
    }

    private static <T> void set(Map<String, AtomicLong> values, String key, long count, Set<String> allowed, String name) {
        if (count < 0) throw new IllegalArgumentException(name + " count must not be negative");
        values.get(fixed(key, allowed, name)).set(count);
    }
    private static String fixed(String value, Set<String> allowed, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (allowed.contains(normalized)) return normalized;
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (allowed.contains(lower)) return lower;
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (allowed.contains(upper)) return upper;
        throw new IllegalArgumentException("unsupported " + name);
    }
    private static void requireDuration(Duration duration, String name) {
        if (duration == null || duration.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
    }
}
