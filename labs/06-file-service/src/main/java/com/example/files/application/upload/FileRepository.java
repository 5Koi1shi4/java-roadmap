package com.example.files.application.upload;

import com.example.files.domain.SafeDisplayName;
import com.example.files.domain.StoredFile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 逻辑文件持久化端口。 */
public interface FileRepository {
    StoredFile create(long ownerId, long blobId, SafeDisplayName displayName, Instant databaseNow);

    default StoredFile create(long ownerId, long blobId, SafeDisplayName displayName) {
        return create(ownerId, blobId, displayName, Instant.now());
    }

    Optional<StoredFile> findActive(UUID fileId);
}
