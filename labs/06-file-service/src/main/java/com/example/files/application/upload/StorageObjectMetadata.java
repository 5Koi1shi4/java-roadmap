package com.example.files.application.upload;

/** 恢复探测所需的可信对象元数据。 */
public record StorageObjectMetadata(long size) {
    public StorageObjectMetadata {
        if (size < 0) throw new IllegalArgumentException("object size must not be negative");
    }
}
