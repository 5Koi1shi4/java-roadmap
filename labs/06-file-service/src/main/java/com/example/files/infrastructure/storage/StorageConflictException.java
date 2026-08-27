package com.example.files.infrastructure.storage;

/** 服务端条件创建拒绝覆盖已有正式对象，属于永久冲突而非可重试网络错误。 */
public final class StorageConflictException extends StorageUnavailableException {
    public StorageConflictException() {
        super("conditional-create", StorageFailureClassifier.FailureClass.PERMANENT);
    }
}
