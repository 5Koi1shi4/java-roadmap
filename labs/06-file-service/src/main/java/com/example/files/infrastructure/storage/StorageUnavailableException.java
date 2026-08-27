package com.example.files.infrastructure.storage;

/** 存储失败的安全边界异常；消息固定，不回显 URL、Key 或凭据。 */
public class StorageUnavailableException extends RuntimeException {
    private final StorageFailureClassifier.FailureClass failureClass;
    private final String operation;

    public StorageUnavailableException(String operation, StorageFailureClassifier.FailureClass failureClass) {
        super("object storage operation unavailable");
        this.operation = operation;
        this.failureClass = failureClass == null
            ? StorageFailureClassifier.FailureClass.PERMANENT : failureClass;
    }

    public StorageUnavailableException(String operation, StorageFailureClassifier.FailureClass failureClass,
                                       Throwable cause) {
        // 不保留底层异常链，避免日志/序列化意外输出 Secret、完整 URL 或 object key。
        super("object storage operation unavailable");
        this.operation = operation;
        this.failureClass = failureClass == null
            ? StorageFailureClassifier.FailureClass.PERMANENT : failureClass;
    }

    public StorageFailureClassifier.FailureClass failureClass() { return failureClass; }
    public String operation() { return operation; }
    public boolean retryable() { return failureClass == StorageFailureClassifier.FailureClass.RETRYABLE; }
}
