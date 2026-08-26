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
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(blob.status()).isEqualTo(BlobStatus.STAGING);
        assertThatThrownBy(blob::beginDeleting).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsOnlyTheDeclaredTransitionsAndReportsLastReferenceRemoval() {
        StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z"));

        blob.ready();
        assertThat(blob.addReference()).isEqualTo(1L);
        ReferenceBecameZero becameZero = blob.removeReference();
        assertThat(becameZero.blobId()).isEqualTo(7L);
        assertThat(becameZero.objectKey()).isEqualTo("blobs/random");
        assertThat(becameZero.generation()).isEqualTo(1L);
        assertThat(blob.status()).isEqualTo(BlobStatus.PENDING_DELETE);
        blob.beginDeletionAttempt(UUID.randomUUID(), Instant.parse("2030-01-01T00:00:30Z"),
            Instant.parse("2030-01-01T00:00:00Z"));
        blob.deleted();
        blob.beginStaging("blobs/new-random", UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(blob.status()).isEqualTo(BlobStatus.STAGING);
    }

    @Test
    void rejectsReferenceCountBelowZero() {
        StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        blob.ready();
        assertThatThrownBy(blob::removeReference).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforcesTheTwentyMiBDomainLimit() {
        assertThat(StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 20L * 1024 * 1024,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z"))).isNotNull();
        assertThatThrownBy(() -> StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 20L * 1024 * 1024 + 1,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAFreshObjectKeyWhenRestagingDeletedBlob() {
        StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        blob.ready();
        blob.addReference();
        blob.removeReference();
        blob.beginDeletionAttempt(UUID.randomUUID(), Instant.parse("2030-01-01T00:01:00Z"),
            Instant.parse("2030-01-01T00:00:00Z"));
        blob.deleted();
        assertThatThrownBy(() -> blob.beginStaging("blobs/random", UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:01:00Z"), Instant.parse("2030-01-01T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesLeasesOnlyWithCallerSuppliedDatabaseTime() {
        assertThatThrownBy(() -> StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:01Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rehydratePreservesDatabaseTimestampsAndRejectsInvalidStateCombinations() {
        Instant created = Instant.parse("2030-01-01T00:00:00.123456Z");
        Instant updated = Instant.parse("2030-01-01T00:00:01.654321Z");
        StoredBlob ready = StoredBlob.rehydrate(8L, "c".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 2L, BlobStatus.READY, 1L, null, null, null, null, null, created, updated);
        assertThat(ready.createdAt()).isEqualTo(created);
        assertThat(ready.updatedAt()).isEqualTo(updated);
        assertThatThrownBy(() -> StoredBlob.rehydrate(8L, "c".repeat(64), "blobs/random", 12L,
            DetectedFileType.PDF, 0L, BlobStatus.DELETING, 1L, null, null, null, null, null, created, updated))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
