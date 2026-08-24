package com.example.search.application.sync;

import java.util.Optional;
import java.util.UUID;

public interface SearchCoordinationRepository {
    void lockShared();

    void lockExclusive();

    default boolean lockSharedAndReadDispatcherPaused() {
        lockShared();
        return false;
    }

    default Optional<UUID> activeRebuildId() {
        throw new UnsupportedOperationException("rebuild coordination is not implemented");
    }
    default void setActiveRebuildId(UUID jobId) {
        throw new UnsupportedOperationException("rebuild coordination is not implemented");
    }
    default boolean clearActiveRebuildId(UUID jobId) {
        throw new UnsupportedOperationException("rebuild coordination is not implemented");
    }
}
