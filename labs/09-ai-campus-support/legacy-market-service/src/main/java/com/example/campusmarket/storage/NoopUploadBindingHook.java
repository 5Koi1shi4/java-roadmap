package com.example.campusmarket.storage;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NoopUploadBindingHook implements UploadBindingHook {
    @Override
    public void afterMediaInserted(UUID sessionId, UUID mediaId) {
        // 生产默认实现不在两次原子写入之间产生副作用。
    }
}
