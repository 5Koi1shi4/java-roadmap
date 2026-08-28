package com.example.files.application.access;

import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.audit.FileServiceMetrics;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.domain.SafeDisplayName;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.net.URI;
import java.util.function.Supplier;
import java.time.Clock;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 协调短授权事务与对象流读取。 */
public final class DownloadService {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadService.class);
    private static final int PREFETCH_BYTES = 8192;
    private final FileAccessRepository access;
    private final AuditRecorder audits;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;
    private final LocalDownloadTokenService tokens;
    private final Duration maxLinkTtl;
    private final Clock clock;
    private final DownloadTelemetry telemetry;
    private final FileServiceMetrics metrics;

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage) {
        this(access, audits, storage, null, null, LocalDownloadTokenService.MAX_TTL, Clock.systemUTC(), new DefaultDownloadTelemetry());
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions) {
        this(access, audits, storage, transactions, null, LocalDownloadTokenService.MAX_TTL, Clock.systemUTC(), new DefaultDownloadTelemetry());
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions, LocalDownloadTokenService tokens) {
        this(access, audits, storage, transactions, tokens, LocalDownloadTokenService.MAX_TTL, Clock.systemUTC(), new DefaultDownloadTelemetry());
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions, LocalDownloadTokenService tokens,
                           Duration maxLinkTtl, Clock clock) {
        this(access, audits, storage, transactions, tokens, maxLinkTtl, clock, new DefaultDownloadTelemetry());
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions, LocalDownloadTokenService tokens,
                           Duration maxLinkTtl, Clock clock, DownloadTelemetry telemetry) {
        this(access, audits, storage, transactions, tokens, maxLinkTtl, clock, telemetry, null);
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions, LocalDownloadTokenService tokens,
                           Duration maxLinkTtl, Clock clock, DownloadTelemetry telemetry,
                           FileServiceMetrics metrics) {
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.audits = java.util.Objects.requireNonNull(audits, "audits");
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.transactions = transactions;
        this.tokens = tokens;
        if (maxLinkTtl == null || maxLinkTtl.isZero() || maxLinkTtl.isNegative()
            || maxLinkTtl.getNano() != 0
            || maxLinkTtl.compareTo(LocalDownloadTokenService.MAX_TTL) > 0) {
            throw new IllegalArgumentException("invalid maximum link TTL");
        }
        this.maxLinkTtl = maxLinkTtl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.telemetry = telemetry == null ? new DefaultDownloadTelemetry() : telemetry;
        this.metrics = metrics == null ? new NoopMetrics() : metrics;
    }

    /** 授权并预打开流；该方法返回前不得开始成功 HTTP 响应。 */
    public DownloadDescriptor authorizeDownload(long actorId, UUID fileId, CorrelationId correlationId) {
        require(actorId, fileId, correlationId);
        Outcome<FileView> outcome = inTransaction(() -> {
            AccessDecision decision = access.findAccess(actorId, fileId);
            if (decision == null || !decision.readable() || decision.view() == null) {
                metrics.recordDownload("authorization", "failed");
                record(correlationId, actorId, AuditAction.DOWNLOAD_AUTHORIZED, fileId,
                    "DENIED", "ACCESS_DENIED");
                return Outcome.<FileView>denied();
            }
            record(correlationId, actorId, AuditAction.DOWNLOAD_AUTHORIZED, fileId,
                "SUCCESS", null);
                metrics.recordDownload("authorization", "success");
                return Outcome.success(decision.view());
        });
        if (outcome.hidden()) throw new ResourceHiddenException();
        FileView view = outcome.value();

        // 只有授权审计事务提交后才解析物理 key。
        FileAccessRepository.DownloadTarget target = access.findDownloadTarget(actorId, fileId)
            .filter(candidate -> candidate.view().fileId().equals(view.fileId()))
            .orElseThrow(ResourceHiddenException::new);
        InputStream opened = null;
        try {
            opened = storage.open(target.objectKey());
            byte[] first = new byte[PREFETCH_BYTES];
            int count = opened.read(first);
            if (count < 0) count = 0;
            InputStream content = new SequenceInputStream(new ByteArrayInputStream(first, 0, count), opened);
            return new DownloadDescriptor(view.fileId(), actorId, target.objectKey(),
                SafeDisplayName.from(view.displayName()).value(), view.mediaType(), view.size(), content);
        } catch (RuntimeException | IOException ex) {
            closeQuietly(opened, ex, correlationId, actorId, fileId);
            recordFailed(actorId, fileId, correlationId, classify(ex));
            if (ex instanceof StorageObjectNotFoundException) throw new ResourceHiddenException();
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new java.io.UncheckedIOException((IOException) ex);
        }
    }

    public DownloadDescriptor authorizeDownload(com.example.files.api.security.RequesterIdentity actor,
                                                UUID fileId, CorrelationId correlationId) {
        if (actor == null) throw new IllegalArgumentException("identity is required");
        return authorizeDownload(actor.userId(), fileId, correlationId);
    }

    public DownloadDescriptor download(long actorId, UUID fileId, CorrelationId correlationId) {
        return authorizeDownload(actorId, fileId, correlationId);
    }

    public void recordCompleted(long actorId, UUID fileId, CorrelationId correlationId) {
        recordResult(correlationId, actorId, fileId, AuditAction.DOWNLOAD_COMPLETED, "SUCCESS", null);
        metrics.recordDownload("transfer", "success");
    }

    public void recordFailed(long actorId, UUID fileId, CorrelationId correlationId, String failureCode) {
        String code = DownloadFailureReason.fromCode(failureCode).name();
            try {
                recordResult(correlationId, actorId, fileId, AuditAction.DOWNLOAD_FAILED, "FAILED", code);
        } finally {
            try { telemetry.failed(fileId, correlationId, code); }
            catch (RuntimeException ex) {
                LOG.warn("download telemetry unavailable correlationId={} fileId={} failureCode={}",
                    correlationId.value(), fileId, code);
            }
            try { metrics.recordDownload("transfer", "failed"); } catch (RuntimeException ignored) { }
        }
    }

    public DownloadLink issueLink(long actorId, UUID fileId, Duration ttl, CorrelationId correlationId) {
        require(actorId, fileId, correlationId);
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0 || ttl.compareTo(maxLinkTtl) > 0) {
            throw new IllegalArgumentException("link ttl must be positive and no more than 2 minutes");
        }
        Instant issuedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(ttl.getSeconds());
        Outcome<FileView> outcome = inTransaction(() -> {
            AccessDecision decision = access.findAccess(actorId, fileId);
            if (decision == null || !decision.readable() || decision.view() == null) {
                record(correlationId, actorId, AuditAction.DOWNLOAD_LINK_ISSUED, fileId,
                    "DENIED", "ACCESS_DENIED");
                return Outcome.<FileView>denied();
            }
            record(correlationId, actorId, AuditAction.DOWNLOAD_LINK_ISSUED, fileId,
                "SUCCESS", null, expiresAt);
            return Outcome.success(decision.view());
        });
        if (outcome.hidden()) throw new ResourceHiddenException();
        FileAccessRepository.DownloadTarget target = access.findDownloadTarget(actorId, fileId)
            .filter(candidate -> candidate.view().fileId().equals(outcome.value().fileId()))
            .orElseThrow(ResourceHiddenException::new);
        Map<String, String> responseHeaders = responseHeaders(outcome.value());
        java.util.Optional<URI> presigned = storage.createPresignedGet(target.objectKey(), ttl, responseHeaders);
        if (presigned.isPresent()) {
            return new DownloadLink(presigned.get().toString(), expiresAt);
        }
        if (tokens == null) throw new IllegalStateException("local download signing is unavailable");
        String token = tokens.issue(actorId, fileId, expiresAt);
        return new DownloadLink("/api/local-downloads/" + token, expiresAt);
    }

    /** 仅传入安全展示名和媒体类型；不会把 bearer URL 或 token 写入审计。 */
    private static Map<String, String> responseHeaders(FileView view) {
        SafeDisplayName display = SafeDisplayName.from(view.displayName());
        String fallback = display.asciiFallback().replace("\"", "_");
        String encoded = URLEncoder.encode(display.value(), StandardCharsets.UTF_8).replace("+", "%20")
            .replace("%7E", "~");
        return Map.of("response-content-type", safeMediaType(view.mediaType()),
            "response-content-disposition", "attachment; filename=\"" + fallback
                + "\"; filename*=UTF-8''" + encoded);
    }

    private static String safeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank() || mediaType.indexOf('\r') >= 0 || mediaType.indexOf('\n') >= 0) {
            return "application/octet-stream";
        }
        try {
            org.springframework.http.MediaType.parseMediaType(mediaType);
            return mediaType;
        } catch (IllegalArgumentException ex) {
            return "application/octet-stream";
        }
    }

    public DownloadLink issueLink(com.example.files.api.security.RequesterIdentity actor, UUID fileId,
                                  Duration ttl, CorrelationId correlationId) {
        if (actor == null) throw new IllegalArgumentException("identity is required");
        return issueLink(actor.userId(), fileId, ttl, correlationId);
    }

    public Duration defaultLinkTtl() {
        return maxLinkTtl;
    }

    public DownloadDescriptor redeem(String token, long actorId, CorrelationId correlationId) {
        if (tokens == null) throw new IllegalStateException("local download signing is unavailable");
        LocalDownloadTokenService.Claims claims;
        try {
            claims = tokens.verify(token, actorId);
        } catch (IllegalArgumentException ex) {
            recordTokenDenied(actorId, correlationId);
            throw new ResourceHiddenException();
        }
        return authorizeDownload(actorId, claims.fileId(), correlationId);
    }

    public DownloadDescriptor redeemToken(String token, long actorId, CorrelationId correlationId) {
        return redeem(token, actorId, correlationId);
    }

    private void recordTokenDenied(long actorId, CorrelationId correlationId) {
        inTransaction(() -> {
            record(correlationId, actorId, AuditAction.DOWNLOAD_TOKEN_DENIED, null, "DENIED", "INVALID_TOKEN");
            return Boolean.TRUE;
        });
    }

    public record DownloadLink(String url, Instant expiresAt) {
        public DownloadLink {
            if (url == null || url.isBlank() || expiresAt == null) throw new IllegalArgumentException("invalid link");
        }
    }

    private void recordResult(CorrelationId correlationId, long actorId, UUID fileId,
                              AuditAction action, String result, String failureCode) {
        require(actorId, fileId, correlationId);
        inTransaction(() -> {
            record(correlationId, actorId, action, fileId, result, failureCode);
            return Boolean.TRUE;
        });
    }

    private void record(CorrelationId correlationId, long actorId, AuditAction action, UUID fileId,
                        String result, String failureCode) {
        record(correlationId, actorId, action, fileId, result, failureCode, null);
    }

    private void record(CorrelationId correlationId, long actorId, AuditAction action, UUID fileId,
                        String result, String failureCode, Instant expiresAt) {
        try {
            audits.record(new AuditEvent(correlationId, actorId, action, fileId, null,
                result, failureCode, null, clock.instant(), expiresAt));
        } catch (RuntimeException ex) {
            throw new DownloadAuditUnavailableException(ex);
        }
    }

    private <T> T inTransaction(Supplier<T> callback) {
        return transactions == null ? callback.get() : transactions.execute(status -> callback.get());
    }

    private static void require(long actorId, UUID fileId, CorrelationId correlationId) {
        if (actorId <= 0 || fileId == null || correlationId == null) throw new IllegalArgumentException("invalid download arguments");
    }

    private static final class NoopMetrics implements FileServiceMetrics {
        @Override public void recordUpload(String result, Duration duration) { }
        @Override public void recordSession(String status) { }
        @Override public void setSessionCount(String status, long count) { }
        @Override public void recordBlob(String status) { }
        @Override public void setBlobCount(String status, long count) { }
        @Override public void setStagingOldest(Duration age) { }
        @Override public void recordCleanupPending(String type) { }
        @Override public void setCleanupPending(String type, long count) { }
        @Override public void recordCleanupRetry(String type, String result) { }
        @Override public void recordDownload(String phase, String result) { }
        @Override public void recordAcl(String action, String result) { }
        @Override public void recordStorageOperation(String operation, String result, Duration duration) { }
    }

    private static String classify(Throwable ex) {
        if (ex instanceof StorageObjectNotFoundException) return "OBJECT_NOT_FOUND";
        if (ex instanceof IOException || ex instanceof java.io.UncheckedIOException) return "STORAGE_READ_FAILED";
        return "STORAGE_READ_FAILED";
    }

    private static void closeQuietly(InputStream input, Throwable original,
                                    CorrelationId correlationId, long actorId, UUID fileId) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException | RuntimeException closeFailure) {
            try {
                original.addSuppressed(closeFailure);
            } catch (RuntimeException ignoredSuppressionFailure) {
                // 即使异常对象拒绝 suppressed，也不能覆盖原始读取失败。
            }
            LOG.warn("download object close failed correlationId={} actorId={} fileId={} failureCode={}",
                correlationId.value(), actorId, fileId, "STORAGE_READ_FAILED");
        }
    }

    private record Outcome<T>(T value, boolean hidden) {
        static <T> Outcome<T> success(T value) { return new Outcome<>(value, false); }
        static <T> Outcome<T> denied() { return new Outcome<>(null, true); }
    }
}
