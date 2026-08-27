package com.example.files.application.access;

import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Coordinates short authorization transactions and streaming object reads. */
public final class DownloadService {
    private static final int PREFETCH_BYTES = 8192;
    private final FileAccessRepository access;
    private final AuditRecorder audits;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;
    private final LocalDownloadTokenService tokens;

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage) {
        this(access, audits, storage, null, null);
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions) {
        this(access, audits, storage, transactions, null);
    }

    public DownloadService(FileAccessRepository access, AuditRecorder audits, ObjectStorage storage,
                           TransactionTemplate transactions, LocalDownloadTokenService tokens) {
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.audits = java.util.Objects.requireNonNull(audits, "audits");
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.transactions = transactions;
        this.tokens = tokens;
    }

    /** Authorize and pre-open the stream; no successful HTTP response can start before this returns. */
    public DownloadDescriptor authorizeDownload(long actorId, UUID fileId, CorrelationId correlationId) {
        require(actorId, fileId, correlationId);
        FileView view = inTransaction(() -> {
            AccessDecision decision = access.findAccess(actorId, fileId);
            if (decision == null || !decision.readable() || decision.view() == null) {
                record(correlationId, actorId, AuditAction.DOWNLOAD_AUTHORIZED, fileId,
                    "DENIED", "ACCESS_DENIED");
                throw new ResourceHiddenException();
            }
            record(correlationId, actorId, AuditAction.DOWNLOAD_AUTHORIZED, fileId,
                "SUCCESS", null);
            return decision.view();
        });

        // Deliberately resolve the physical key after the authorization audit transaction commits.
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
            closeQuietly(opened);
            recordResult(correlationId, actorId, fileId, AuditAction.DOWNLOAD_FAILED,
                "FAILED", classify(ex));
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
    }

    public void recordFailed(long actorId, UUID fileId, CorrelationId correlationId, String failureCode) {
        recordResult(correlationId, actorId, fileId, AuditAction.DOWNLOAD_FAILED, "FAILED",
            failureCode == null ? "STREAM_FAILED" : failureCode);
    }

    public DownloadLink issueLink(long actorId, UUID fileId, Duration ttl, CorrelationId correlationId) {
        require(actorId, fileId, correlationId);
        if (tokens == null) throw new IllegalStateException("local download signing is unavailable");
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(LocalDownloadTokenService.MAX_TTL) > 0) {
            throw new IllegalArgumentException("link ttl must be positive and no more than 2 minutes");
        }
        FileView view = inTransaction(() -> {
            AccessDecision decision = access.findAccess(actorId, fileId);
            if (decision == null || !decision.readable() || decision.view() == null) {
                record(correlationId, actorId, AuditAction.DOWNLOAD_LINK_ISSUED, fileId,
                    "DENIED", "ACCESS_DENIED");
                throw new ResourceHiddenException();
            }
            record(correlationId, actorId, AuditAction.DOWNLOAD_LINK_ISSUED, fileId,
                "SUCCESS", null);
            return decision.view();
        });
        Instant expiresAt = Instant.now().plus(ttl);
        String token = tokens.issue(actorId, fileId, ttl);
        return new DownloadLink("/api/local-downloads/" + token, expiresAt);
    }

    public DownloadLink issueLink(com.example.files.api.security.RequesterIdentity actor, UUID fileId,
                                  Duration ttl, CorrelationId correlationId) {
        if (actor == null) throw new IllegalArgumentException("identity is required");
        return issueLink(actor.userId(), fileId, ttl, correlationId);
    }

    public DownloadDescriptor redeem(String token, long actorId, CorrelationId correlationId) {
        if (tokens == null) throw new IllegalStateException("local download signing is unavailable");
        LocalDownloadTokenService.Claims claims;
        try {
            claims = tokens.verify(token, actorId);
        } catch (IllegalArgumentException ex) {
            throw new ResourceHiddenException();
        }
        return authorizeDownload(actorId, claims.fileId(), correlationId);
    }

    public DownloadDescriptor redeemToken(String token, long actorId, CorrelationId correlationId) {
        return redeem(token, actorId, correlationId);
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
        try {
            audits.record(new AuditEvent(correlationId, actorId, action, fileId, null,
                result, failureCode, null, Instant.now()));
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

    private static String classify(Throwable ex) {
        if (ex instanceof StorageObjectNotFoundException) return "OBJECT_NOT_FOUND";
        if (ex instanceof IOException || ex instanceof java.io.UncheckedIOException) return "STORAGE_READ_FAILED";
        return "STORAGE_READ_FAILED";
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }
}
