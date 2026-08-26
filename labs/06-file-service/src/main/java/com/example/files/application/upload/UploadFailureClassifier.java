package com.example.files.application.upload;

/** 将上传异常压缩为不泄露底层细节的稳定失败分类。 */
public final class UploadFailureClassifier {
    public String classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.io.IOException || current instanceof java.io.UncheckedIOException) return "STORAGE_IO";
        }
        if (error instanceof UploadRejectedException rejected) return rejected.code();
        if (error instanceof StorageCoordinationUnavailableException) return "STORAGE_COORDINATION_UNAVAILABLE";
        if (error instanceof java.io.IOException) return "STORAGE_IO";
        return "UPLOAD_FAILED";
    }
}
