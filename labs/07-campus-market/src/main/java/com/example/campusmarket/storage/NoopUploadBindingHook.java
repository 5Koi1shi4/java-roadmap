package com.example.campusmarket.storage;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NoopUploadBindingHook implements UploadBindingHook {
    @Override
    public void afterMediaInserted(UUID sessionId, UUID mediaId) {
        // Production default: no side effect between the two atomic writes.
    }
}
