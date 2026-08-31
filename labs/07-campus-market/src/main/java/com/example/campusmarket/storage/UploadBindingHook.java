package com.example.campusmarket.storage;

import java.util.UUID;

/** Narrow transaction hook used to observe a media row before session completion. */
@FunctionalInterface
public interface UploadBindingHook {
    void afterMediaInserted(UUID sessionId, UUID mediaId);
}
