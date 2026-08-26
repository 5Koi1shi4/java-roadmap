package com.example.files.unit;

import com.example.files.domain.BlobStatus;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.ReferenceBecameZero;
import com.example.files.domain.StoredBlob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredBlobTest {

    @Test
    void rejectsUnsafeNamesAndInvalidBlobTransitions() {
        StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));

        assertThat(blob.status()).isEqualTo(BlobStatus.STAGING);
        assertThatThrownBy(blob::beginDeleting).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsOnlyTheDeclaredTransitionsAndReportsLastReferenceRemoval() {
        StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));

        blob.ready();
        assertThat(blob.addReference()).isEqualTo(1L);
        ReferenceBecameZero becameZero = blob.removeReference();
        assertThat(becameZero.blobId()).isEqualTo(7L);
        assertThat(blob.status()).isEqualTo(BlobStatus.PENDING_DELETE);
        blob.beginDeletionAttempt(UUID.randomUUID(), Instant.now().plusSeconds(60));
        blob.deleted();
        blob.beginStaging(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));
        assertThat(blob.status()).isEqualTo(BlobStatus.STAGING);
    }

    @Test
    void rejectsReferenceCountBelowZero() {
        StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));
        blob.ready();
        assertThatThrownBy(blob::removeReference).isInstanceOf(IllegalStateException.class);
    }
}
