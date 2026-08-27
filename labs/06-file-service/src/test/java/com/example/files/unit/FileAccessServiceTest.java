package com.example.files.unit;

import com.example.files.application.access.FileAccessRepository;
import com.example.files.application.access.FileAccessService;
import com.example.files.application.access.FileView;
import com.example.files.application.access.AccessDecision;
import com.example.files.application.access.ResourceHiddenException;
import com.example.files.application.audit.AuditRecorder;
import com.example.files.application.audit.CorrelationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

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
