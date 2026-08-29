package com.example.files.application.access;

import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.audit.FileServiceMetrics;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 逻辑文件 ACL 用例。所有安全敏感决策和对应审计都在同一短事务内完成。
 * 不识别管理员角色：唯一旁路是文件 owner，grantee 只有读取能力。
 */
public final class FileAccessService {
    private final FileAccessRepository repository;
    private final AuditRecorder audits;
    private final TransactionTemplate transactions;
    private final FileServiceMetrics metrics;

    /** 纯单元测试构造器；生产环境应使用带事务模板的构造器。 */
    public FileAccessService(FileAccessRepository repository, AuditRecorder audits) {
        this(repository, audits, null, null);
    }

    public FileAccessService(FileAccessRepository repository, AuditRecorder audits,
                             TransactionTemplate transactions) {
        this(repository, audits, transactions, null);
    }

    public FileAccessService(FileAccessRepository repository, AuditRecorder audits,
                             TransactionTemplate transactions, FileServiceMetrics metrics) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.audits = java.util.Objects.requireNonNull(audits, "audits");
        this.transactions = transactions;
        this.metrics = metrics == null ? new NoopMetrics() : metrics;
    }

    public FileView getMetadata(long actorId, UUID fileId, CorrelationId correlationId) {
        requireActorAndFile(actorId, fileId, correlationId);
        return inTransaction(() -> {
            AccessDecision decision = repository.findAccess(actorId, fileId);
            if (decision == null || !decision.readable() || decision.view() == null) {
                return denied(correlationId, actorId, fileId, null, AuditAction.FILE_ACCESSED);
            }
            record(correlationId, actorId, AuditAction.FILE_ACCESSED, fileId, null, "SUCCESS", null);
            return Outcome.success(decision.view());
        });
    }

    public void grantRead(long actorId, UUID fileId, long granteeId, CorrelationId correlationId) {
        requireActorAndFile(actorId, fileId, correlationId);
        if (granteeId <= 0) throw new IllegalArgumentException("granteeId must be positive");
        if (actorId == granteeId) throw new IllegalArgumentException("cannot grant access to owner");
        runAcl("grant", () -> {
            if (!repository.isActiveOwnerForUpdate(actorId, fileId)) {
                return this.<Void>denied(correlationId, actorId, fileId, granteeId, AuditAction.ACCESS_GRANTED);
            }
            repository.grant(actorId, fileId, granteeId);
            record(correlationId, actorId, AuditAction.ACCESS_GRANTED, fileId, granteeId, "SUCCESS", null);
            return Outcome.<Void>success(null);
        });
    }

    public void revokeRead(long actorId, UUID fileId, long granteeId, CorrelationId correlationId) {
        requireActorAndFile(actorId, fileId, correlationId);
        if (granteeId <= 0) throw new IllegalArgumentException("granteeId must be positive");
        if (actorId == granteeId) throw new IllegalArgumentException("cannot revoke owner access");
        runAcl("revoke", () -> {
            if (!repository.isActiveOwnerForUpdate(actorId, fileId)) {
                return this.<Void>denied(correlationId, actorId, fileId, granteeId, AuditAction.ACCESS_REVOKED);
            }
            repository.revoke(actorId, fileId, granteeId);
            record(correlationId, actorId, AuditAction.ACCESS_REVOKED, fileId, granteeId, "SUCCESS", null);
            return Outcome.<Void>success(null);
        });
    }

    public void delete(long actorId, UUID fileId, CorrelationId correlationId) {
        requireActorAndFile(actorId, fileId, correlationId);
        runAcl("delete", () -> {
            if (!repository.isOwnerForUpdate(actorId, fileId)) {
                return this.<Void>denied(correlationId, actorId, fileId, null, AuditAction.FILE_DELETED);
            }
            repository.delete(actorId, fileId);
            record(correlationId, actorId, AuditAction.FILE_DELETED, fileId, null, "SUCCESS", null);
            return Outcome.<Void>success(null);
        });
    }

    public FileView getMetadata(com.example.files.api.security.RequesterIdentity actor,
                                UUID fileId, CorrelationId correlationId) {
        if (actor == null) throw new IllegalArgumentException("identity is required");
        return getMetadata(actor.userId(), fileId, correlationId);
    }

    private <T> Outcome<T> denied(CorrelationId correlationId, long actorId, UUID fileId, Long target,
                        AuditAction action) {
        // 先写入拒绝审计并返回事务结果；事务提交后由 inTransaction 统一抛隐藏异常。
        record(correlationId, actorId, action, fileId, target, "DENIED", "ACCESS_DENIED");
        return Outcome.hiddenOutcome();
    }

    private void runAcl(String action, Supplier<Outcome<Void>> operation) {
        Outcome<Void> outcome = executeTransaction(operation);
        // TransactionTemplate returns only after a successful commit. Recording here
        // prevents a rollback from leaving a false success counter behind.
        metrics.recordAcl(action, outcome.hidden() ? "failed" : "success");
        if (outcome.hidden()) throw new ResourceHiddenException();
    }

    private void record(CorrelationId correlationId, long actorId, AuditAction action, UUID fileId,
                        Long target, String result, String failureCode) {
        audits.record(new AuditEvent(correlationId, actorId, action, fileId, target,
            result, failureCode, null, Instant.now()));
    }

    private <T> T inTransaction(Supplier<Outcome<T>> operation) {
        Outcome<T> outcome = executeTransaction(operation);
        if (outcome == null) throw new IllegalStateException("access transaction returned no outcome");
        if (outcome.hidden()) throw new ResourceHiddenException();
        return outcome.value();
    }

    private <T> Outcome<T> executeTransaction(Supplier<Outcome<T>> operation) {
        return transactions == null ? operation.get() : transactions.execute(status -> operation.get());
    }

    private record Outcome<T>(T value, boolean hidden) {
        static <T> Outcome<T> success(T value) { return new Outcome<>(value, false); }
        static <T> Outcome<T> hiddenOutcome() { return new Outcome<>(null, true); }
    }

    private static void requireActorAndFile(long actorId, UUID fileId, CorrelationId correlationId) {
        if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
        if (fileId == null || correlationId == null) throw new IllegalArgumentException("fileId and correlationId are required");
    }

    private static final class NoopMetrics implements FileServiceMetrics {
        @Override public void recordUpload(String result, java.time.Duration duration) { }
        @Override public void recordSession(String status) { }
        @Override public void setSessionCount(String status, long count) { }
        @Override public void recordBlob(String status) { }
        @Override public void setBlobCount(String status, long count) { }
        @Override public void setStagingOldest(java.time.Duration age) { }
        @Override public void recordCleanupPending(String type) { }
        @Override public void setCleanupPending(String type, long count) { }
        @Override public void recordCleanupRetry(String type, String result) { }
        @Override public void recordDownload(String phase, String result) { }
        @Override public void recordAcl(String action, String result) { }
        @Override public void recordStorageOperation(String operation, String result, java.time.Duration duration) { }
    }
}
