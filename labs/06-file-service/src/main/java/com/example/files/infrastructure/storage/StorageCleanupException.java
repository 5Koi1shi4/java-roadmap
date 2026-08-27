package com.example.files.infrastructure.storage;

/** 正式对象已提交但临时对象删除失败，交由补偿清理，不回滚正式对象。 */
public final class StorageCleanupException extends StorageUnavailableException {
    public StorageCleanupException(Throwable cause) {
        super("delete-temporary-object", StorageFailureClassifier.FailureClass.RETRYABLE, cause);
    }
}
