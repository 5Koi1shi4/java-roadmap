package com.example.search.application.sync;

public interface SearchCoordinationRepository {
    void lockShared();

    void lockExclusive();

    default boolean lockSharedAndReadDispatcherPaused() {
        lockShared();
        return false;
    }
}
