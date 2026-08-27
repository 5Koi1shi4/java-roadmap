package com.example.files.unit;

import com.example.files.application.access.AccessDecision;
import com.example.files.application.access.DownloadService;
import com.example.files.application.access.FileAccessRepository;
import com.example.files.application.access.ResourceHiddenException;
import com.example.files.application.access.LocalDownloadTokenService;
import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.StorageObjectMetadata;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadServiceTest {
    @Test
    void malformedTokenIsHiddenOnlyAfterTokenDeniedAudit() {
        java.util.List<AuditEvent> events = new java.util.ArrayList<>();
        DownloadService service = new DownloadService(new EmptyAccess(), events::add, new EmptyStorage(), null,
            new LocalDownloadTokenService("01234567890123456789012345678901"));
        assertThatThrownBy(() -> service.redeem("malformed", 7L, CorrelationId.random()))
            .isInstanceOf(ResourceHiddenException.class);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(AuditAction.DOWNLOAD_TOKEN_DENIED);
        assertThat(events.get(0).fileId()).isNull();
        assertThat(events.get(0).failureCode()).isEqualTo("INVALID_TOKEN");
    }

    private static final class EmptyAccess implements FileAccessRepository {
        @Override public AccessDecision findAccess(long actorId, UUID fileId) { return AccessDecision.hidden(); }
    }

    private static final class EmptyStorage implements ObjectStorage {
        @Override public TemporaryObject writeTemporary(String k, InputStream s, long m) { throw new UnsupportedOperationException(); }
        @Override public void commit(String t, String o) { throw new UnsupportedOperationException(); }
        @Override public InputStream open(String k) { throw new UnsupportedOperationException(); }
        @Override public StorageObjectMetadata stat(String k) { throw new UnsupportedOperationException(); }
        @Override public void delete(String k) { throw new UnsupportedOperationException(); }
        @Override public Optional<URI> createPresignedGet(String k, Duration t, Map<String, String> h) { return Optional.empty(); }
    }
}
