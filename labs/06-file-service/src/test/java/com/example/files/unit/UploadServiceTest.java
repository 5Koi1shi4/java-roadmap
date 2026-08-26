package com.example.files.unit;

import com.example.files.application.audit.CorrelationId;
import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.application.upload.BlobReservation;
import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StagingWaitPolicy;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadCommand;
import com.example.files.application.upload.UploadFailureClassifier;
import com.example.files.application.upload.UploadResult;
import com.example.files.application.upload.UploadService;
import com.example.files.application.upload.UploadTransactionService;
import com.example.files.config.FileServiceProperties;
import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.UploadSession;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 上传应用服务的最小编排回归测试。 */
class UploadServiceTest {
    @Test
    void neverRunsStorageIoInsideTransaction() {
        byte[] pdf = "%PDF-1.7 test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        RecordingStorage storage = new RecordingStorage();
        FakeTransactions transactions = new FakeTransactions();
        UploadService service = new UploadService(transactions, new com.example.files.application.upload.UploadInspector(),
            storage, new StagingWaitPolicy(Duration.ofSeconds(1), Duration.ofMillis(1)),
            new NoopCleanupTasks(), new UploadFailureClassifier());

        UploadResult result = service.upload(new UploadCommand(7L, "report.pdf", "application/pdf",
            pdf.length, new ByteArrayInputStream(pdf), CorrelationId.random()));

        assertThat(result.fileId()).isEqualTo(transactions.fileId);
        assertThat(storage.transactionActiveDuringWrite).isFalse();
        assertThat(storage.transactionActiveDuringCommit).isFalse();
        assertThat(storage.transactionActiveDuringDelete).isFalse();
    }

    @Test
    void waitingReservationMustBeResolvedBeforeObjectCommit() {
        byte[] pdf = "%PDF-1.7 wait".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        RecordingStorage storage = new RecordingStorage();
        FakeTransactions transactions = new FakeTransactions();
        transactions.waitOnce = true;
        UploadService service = new UploadService(transactions, new com.example.files.application.upload.UploadInspector(),
            storage, new StagingWaitPolicy(Duration.ofSeconds(1), Duration.ofMillis(1)),
            new NoopCleanupTasks(), new UploadFailureClassifier());

        service.upload(new UploadCommand(7L, "wait.pdf", "application/pdf", pdf.length,
            new ByteArrayInputStream(pdf), CorrelationId.random()));

        assertThat(transactions.resolveCalls).isOne();
        assertThat(storage.committedObjectKey).startsWith("blobs/");
    }

    private static final class FakeTransactions implements UploadService.Transactions {
        private final UUID sessionId = UUID.randomUUID();
        private final UUID token = UUID.randomUUID();
        private final UUID fileId = UUID.randomUUID();
        private boolean waitOnce;
        private int resolveCalls;
        private String committedObjectKey;
        private final UploadSession session = UploadSession.newSession(sessionId, 7L, "tmp/" + UUID.randomUUID(),
            token, SafeDisplayName.from("report.pdf"), "application/pdf", Duration.ofHours(1),
            Duration.ofMinutes(2), Instant.now());

        @Override public UploadSession begin(long actorId, SafeDisplayName name, String declaredType) { return session; }
        @Override public BlobReservation reserve(UUID id, UUID owner, InspectedUpload upload) {
            if (waitOnce) return BlobReservation.waiting();
            return BlobReservation.newStaging(id, owner, 1L, "blobs/" + UUID.randomUUID());
        }
        @Override public BlobReservation resolve(UUID id, UUID owner, InspectedUpload upload) {
            resolveCalls++;
            waitOnce = false;
            return reserve(id, owner, upload);
        }
        @Override public UploadResult finalizeUpload(BlobReservation.Granted reservation) {
            return new UploadResult(fileId, "report.pdf", "application/pdf", 13L, Instant.now());
        }
        @Override public UploadResult attachReadyBlob(BlobReservation.ReadyReuse reservation) {
            return finalizeUpload(reservation);
        }
        @Override public void recordFailure(UUID id, UUID owner, String failureCode) { }
    }

    private static final class RecordingStorage implements ObjectStorage {
        private boolean transactionActiveDuringWrite;
        private boolean transactionActiveDuringCommit;
        private boolean transactionActiveDuringDelete;
        private byte[] bytes;
        private String committedObjectKey;

        @Override public TemporaryObject writeTemporary(String key, InputStream source, long maxBytes) {
            transactionActiveDuringWrite = TransactionSynchronizationManager.isActualTransactionActive();
            try {
                bytes = source.readAllBytes();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return new TemporaryObject(key, bytes.length);
        }
        @Override public void commit(String tempKey, String objectKey) {
            transactionActiveDuringCommit = TransactionSynchronizationManager.isActualTransactionActive();
            committedObjectKey = objectKey;
        }
        @Override public InputStream open(String objectKey) { return new ByteArrayInputStream(bytes); }
        @Override public void delete(String objectKey) {
            transactionActiveDuringDelete = TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override public Optional<URI> createPresignedGet(String objectKey, Duration ttl, Map<String, String> headers) {
            return Optional.empty();
        }
    }

    private static final class NoopCleanupTasks implements CleanupTaskRepository {
        @Override public void enqueueTemp(UploadSession session, String tempKey) { }
        @Override public void enqueueTempIfEligible(UUID sessionId) { }
    }
}
