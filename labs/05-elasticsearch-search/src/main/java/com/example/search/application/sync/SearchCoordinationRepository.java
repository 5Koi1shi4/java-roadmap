package com.example.search.application.sync;

public interface SearchCoordinationRepository {
    void lockShared();

    default boolean lockSharedAndReadDispatcherPaused() {
        lockShared();
        return false;
    }
}
