package com.example.files.unit;

import com.example.files.application.access.FileAccessRepository;
import com.example.files.application.access.FileAccessService;
import com.example.files.application.access.FileView;
import com.example.files.application.access.AccessDecision;
import com.example.files.application.access.ResourceHiddenException;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
import org.junit.jupiter.api.Test;
import com.example.files.application.audit.FileServiceMetrics;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task7 ACL 用例：私有默认、只读授权、隐藏资源及删除幂等。 */
class FileAccessServiceTest {
    private static final UUID FILE = UUID.randomUUID();
    private static final long OWNER = 7L;
    private static final long READER = 8L;

    @Test
    void granteeCanReadButCannotGrantOrDelete() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository(OWNER, FILE);
        FileAccessService service = service(repository);
        CorrelationId correlationId = CorrelationId.random();

        service.grantRead(OWNER, FILE, READER, correlationId);
        assertThat(service.getMetadata(READER, FILE, correlationId)).isNotNull();
        assertThatThrownBy(() -> service.grantRead(READER, FILE, 9L, correlationId))
            .isInstanceOf(ResourceHiddenException.class);
        assertThatThrownBy(() -> service.delete(READER, FILE, correlationId))
            .isInstanceOf(ResourceHiddenException.class);
    }

    @Test
    void selfGrantIsRejectedAndRepeatedGrantRevokeAreIdempotent() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository(OWNER, FILE);
        FileAccessService service = service(repository);
        CorrelationId correlationId = CorrelationId.random();
        assertThatThrownBy(() -> service.grantRead(OWNER, FILE, OWNER, correlationId))
            .isInstanceOf(IllegalArgumentException.class);
        service.grantRead(OWNER, FILE, READER, correlationId);
        service.grantRead(OWNER, FILE, READER, correlationId);
        service.revokeRead(OWNER, FILE, READER, correlationId);
        service.revokeRead(OWNER, FILE, READER, correlationId);
        assertThat(repository.grantCount).isOne();
        assertThat(repository.revokeCount).isEqualTo(2);
    }

    @Test
    void aclSuccessMetricIsNotRecordedWhenDatabaseCommitFails() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository(OWNER, FILE);
        CountingMetrics metrics = new CountingMetrics();
        FileServiceMetrics failingCommitMetrics = metrics;
        FileAccessService service = new FileAccessService(repository, event -> { },
            new TransactionTemplate(new CommitFailingTransactionManager()), failingCommitMetrics);

        assertThatThrownBy(() -> service.grantRead(OWNER, FILE, READER, CorrelationId.random()))
            .isInstanceOf(RuntimeException.class);
        assertThat(metrics.aclSuccesses).isZero();
    }

    private static final class CommitFailingTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new DefaultTransactionStatus(null, true, true, false, false, null);
        }
        @Override public void commit(TransactionStatus status) { throw new IllegalStateException("commit failed"); }
        @Override public void rollback(TransactionStatus status) { }
    }

    private static final class CountingMetrics implements FileServiceMetrics {
        private int aclSuccesses;
        @Override public void recordAcl(String action, String result) { if ("success".equals(result)) aclSuccesses++; }
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
        @Override public void recordStorageOperation(String operation, String result, Duration duration) { }
    }

    private static FileAccessService service(FileAccessRepository repository) {
        return new FileAccessService(repository, event -> {});
    }

    private static final class InMemoryAccessRepository implements FileAccessRepository {
        private final long owner;
        private final UUID file;
        private boolean deleted;
        private boolean granted;
        private int grantCount;
        private int revokeCount;

        private InMemoryAccessRepository(long owner, UUID file) { this.owner = owner; this.file = file; }

        @Override public AccessDecision findAccess(long actorId, UUID fileId) {
            if (!file.equals(fileId) || deleted) return AccessDecision.hidden();
            return AccessDecision.of(new FileView(file, owner, "note.txt", "text/plain", 4L, Instant.EPOCH), actorId == owner || (actorId == READER && granted));
        }
        @Override public boolean grant(long actorId, UUID fileId, long granteeId) { if (!granted) grantCount++; granted = true; return true; }
        @Override public boolean revoke(long actorId, UUID fileId, long granteeId) { revokeCount++; granted = false; return true; }
        @Override public boolean delete(long actorId, UUID fileId) { deleted = true; return true; }
    }
}
