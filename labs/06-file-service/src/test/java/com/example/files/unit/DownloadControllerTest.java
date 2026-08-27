package com.example.files.unit;

import com.example.files.api.DownloadController;
import com.example.files.api.security.RequesterIdentity;
import com.example.files.application.access.DownloadDescriptor;
import com.example.files.application.access.DownloadService;
import com.example.files.application.audit.AuditAction;
import com.example.files.application.audit.AuditEvent;
import com.example.files.api.security.RequesterIdentityResolver;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DownloadControllerTest {
    @Test
    void headerFailureWithRuntimeCloseStillRecordsFailedAudit() {
        UUID fileId = UUID.randomUUID();
        ArrayList<AuditEvent> events = new ArrayList<>();
        DownloadService downloads = mock(DownloadService.class);
        RequesterIdentityResolver identities = request -> Optional.of(new RequesterIdentity(7L));
        InputStream stream = new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() { throw new IllegalStateException("close backend detail"); }
        };
        DownloadDescriptor descriptor = new DownloadDescriptor(fileId, 7L, "blobs/" + fileId,
            "a".repeat(256), "application/pdf", 0L, stream);
        when(downloads.authorizeDownload(anyLong(), any(UUID.class), any())).thenReturn(descriptor);
        doAnswer(invocation -> {
            events.add(new AuditEvent(invocation.getArgument(2), 7L, AuditAction.DOWNLOAD_FAILED,
                fileId, null, "FAILED", "HEADER_FAILED"));
            return null;
        }).when(downloads).recordFailed(anyLong(), any(UUID.class), any(), any());
        DownloadController controller = new DownloadController(downloads, identities);

        assertThatThrownBy(() -> controller.content(fileId.toString(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        assertThat(events).extracting(AuditEvent::failureCode).containsExactly("HEADER_FAILED");
    }
}
