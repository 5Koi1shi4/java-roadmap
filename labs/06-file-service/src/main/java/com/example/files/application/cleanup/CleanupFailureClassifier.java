package com.example.files.application.cleanup;

import com.example.files.infrastructure.storage.StorageFailureClassifier;
import com.example.files.infrastructure.storage.StorageUnavailableException;

/** 清理只根据已分类的存储异常决定是否重试。 */
public final class CleanupFailureClassifier {
    public boolean retryable(Throwable error) {
        if (error instanceof StorageUnavailableException unavailable) return unavailable.retryable();
        return new StorageFailureClassifier().isRetryable(error);
    }
}
