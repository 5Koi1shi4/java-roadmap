package com.example.campusmarket.api;

import com.example.campusmarket.review.ReviewService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MultipartException;

import java.nio.charset.StandardCharsets;

/** MVC 最后一道协议边界；底层异常只映射为固定中文消息。 */
@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class, MultipartException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> badRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
    }

    @ExceptionHandler(ReviewService.NotFoundException.class)
    public ResponseEntity<ApiError> notFound(Exception ignored) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    @ExceptionHandler(ReviewService.ConflictException.class)
    public ResponseEntity<ApiError> conflict(Exception ignored) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "请求与当前状态冲突");
    }

    @ExceptionHandler(ReviewService.UnprocessableException.class)
    public ResponseEntity<ApiError> unprocessable(Exception ignored) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", "请求不满足业务条件");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> dependencyUnavailable(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "依赖服务暂时不可用");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JSON)
            .body(new ApiError(code, message, SecurityConfiguration.correlationId()));
    }

    /** 与协议文档保持同一入口，便于 Web 层调用方按 handler 读取固定错误模型。 */
    public record ApiError(String code, String message, String correlationId) { }
}
