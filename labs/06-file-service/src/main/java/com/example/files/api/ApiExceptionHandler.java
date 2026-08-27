package com.example.files.api;

import com.example.files.api.security.RequesterUnauthenticatedException;
import com.example.files.application.upload.StorageCoordinationUnavailableException;
import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.application.upload.UploadRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 将边界异常映射为稳定中文错误，不回显底层消息。 */
@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    @ExceptionHandler(RequesterUnauthenticatedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(RequesterUnauthenticatedException ex,
                                                        HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex,
                                                    HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件超过大小限制", request);
    }

    @ExceptionHandler(UploadRejectedException.class)
    public ResponseEntity<ApiError> handleUploadRejected(UploadRejectedException ex,
                                                          HttpServletRequest request) {
        if ("FILE_TOO_LARGE".equals(ex.code())) {
            return response(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件超过大小限制", request);
        }
        return response(HttpStatus.BAD_REQUEST, "INVALID_FILE", "文件内容或名称无效", request);
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class,
        IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效", request);
    }

    @ExceptionHandler({StorageCoordinationUnavailableException.class, StorageObjectNotFoundException.class,
        DataAccessException.class, UncheckedIOException.class})
    public ResponseEntity<ApiError> handleUnavailable(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "文件服务暂不可用", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "文件服务暂不可用", request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                              HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(JSON_UTF8);
        return new ResponseEntity<>(new ApiError(code, message, correlationIdValue(request)), headers, status);
    }

    private static String correlationIdValue(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        if (value instanceof com.example.files.application.audit.CorrelationId id) return id.value();
        if (value instanceof String text && com.example.files.application.audit.CorrelationId.isValid(text)) return text;
        return com.example.files.application.audit.CorrelationId.random().value();
    }
}
