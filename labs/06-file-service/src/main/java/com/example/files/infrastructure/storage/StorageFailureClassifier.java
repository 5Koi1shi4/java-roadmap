package com.example.files.infrastructure.storage;

import io.minio.errors.ErrorResponseException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;

/** 将 MinIO/网络异常压缩为可安全用于重试决策的分类。 */
public final class StorageFailureClassifier {
    public enum FailureClass { RETRYABLE, PERMANENT }

    public FailureClass classify(Throwable error) {
        if (error == null) return FailureClass.PERMANENT;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ErrorResponseException response) {
                int status = httpStatus(response);
                if (status == 408 || status == 429 || status >= 500) return FailureClass.RETRYABLE;
                String code = response.errorResponse() == null ? "" : response.errorResponse().code();
                if ("SlowDown".equalsIgnoreCase(code) || "TooManyRequests".equalsIgnoreCase(code)
                    || "InternalError".equalsIgnoreCase(code) || "ServiceUnavailable".equalsIgnoreCase(code)) {
                    return FailureClass.RETRYABLE;
                }
                return FailureClass.PERMANENT;
            }
            if (current instanceof ServerException server) {
                int status = server.statusCode();
                return status == 408 || status == 429 || status >= 500
                    ? FailureClass.RETRYABLE : FailureClass.PERMANENT;
            }
            if (current instanceof InvalidResponseException) return FailureClass.RETRYABLE;
            if (current instanceof SocketTimeoutException || current instanceof IOException) {
                return FailureClass.RETRYABLE;
            }
            String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (name.contains("timeout") || name.contains("connection") || name.contains("connect")) {
                return FailureClass.RETRYABLE;
            }
        }
        return FailureClass.PERMANENT;
    }

    private static int httpStatus(ErrorResponseException exception) {
        try {
            Object response = ErrorResponseException.class.getMethod("response").invoke(exception);
            return response == null ? -1 : (int) response.getClass().getMethod("code").invoke(response);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    public boolean isRetryable(Throwable error) {
        return classify(error) == FailureClass.RETRYABLE;
    }
}
