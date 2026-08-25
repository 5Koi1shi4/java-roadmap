package com.example.search.api;

import com.example.search.application.product.ProductNotFoundException;
import com.example.search.application.product.ProductVersionConflictException;
import com.example.search.application.search.SearchUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
    }
    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return response(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在"); }
    @ExceptionHandler(ProductVersionConflictException.class)
    ResponseEntity<ApiError> conflict() { return response(HttpStatus.CONFLICT, "VERSION_CONFLICT", "商品版本冲突"); }
    @ExceptionHandler(SearchUnavailableException.class)
    ResponseEntity<ApiError> unavailable() { return response(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "搜索服务暂不可用"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal() { return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂不可用"); }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(new ApiError(code, message));
    }
}
