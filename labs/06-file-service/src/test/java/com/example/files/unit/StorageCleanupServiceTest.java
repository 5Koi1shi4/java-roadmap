package com.example.files.unit;

import com.example.files.application.cleanup.*;
import com.example.files.application.upload.*;
import com.example.files.domain.UploadSession;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StorageCleanupServiceTest {
    @Test
    void doesNotDeleteValidatedSessionWhenLeaseExpiredButTtlIsStillValid() {
        Fixture f = new Fixture();
        when(f.sessions.isExpiredAtDatabaseTime(f.sessionId)).thenReturn(false);
        StorageCleanupService service = f.service();
        CleanupSummary summary = service.runBatch("worker");
        verify(f.storage, never()).delete(anyString());
        assertThat(summary.retried()).isOne();
    }

    @Test
    void doesNotDeleteWhenTaskObjectKeyDiffersFromSession() {
        Fixture f = new Fixture();
        when(f.sessions.isExpiredAtDatabaseTime(f.sessionId)).thenReturn(true);
        f.task = new ClaimedCleanup(f.task.taskId(), CleanupTaskType.TEMP_OBJECT, f.sessionId.toString(), 1,
            "tmp/00000000-0000-0000-0000-000000000099", f.task.claimToken(), 0);
        CleanupSummary summary = f.service().runBatch("worker");
        verify(f.storage, never()).delete(anyString());
        assertThat(summary.retried()).isOne();
    }

    private static final class Fixture {
        final UUID sessionId = UUID.randomUUID();
        final CleanupTaskRepository tasks = mock(CleanupTaskRepository.class);
        final ObjectStorage storage = mock(ObjectStorage.class);
        final UploadSessionRepository sessions = mock(UploadSessionRepository.class);
        final BlobRepository blobs = mock(BlobRepository.class);
        ClaimedCleanup task = new ClaimedCleanup(UUID.randomUUID(), CleanupTaskType.TEMP_OBJECT, sessionId.toString(), 1,
            "tmp/00000000-0000-0000-0000-000000000001", UUID.randomUUID(), 0);
        Fixture() {
            UploadSession session = mock(UploadSession.class);
            when(session.status()).thenReturn(com.example.files.domain.UploadSessionStatus.VALIDATED);
            when(session.tempKey()).thenReturn(task.objectKey());
            when(sessions.find(sessionId)).thenReturn(Optional.of(session));
            when(tasks.claimBatch(anyString(), anyInt(), any())).thenAnswer(invocation -> List.of(task));
            when(tasks.retry(any(), any(), any(), any())).thenReturn(true);
            when(tasks.complete(any(), any())).thenReturn(true);
        }
        StorageCleanupService service() {
            return new StorageCleanupService(tasks, storage, sessions, blobs, 50, Duration.ofSeconds(30), new CleanupRetrySchedule());
        }
    }
}
