package com.example.campusmarket.warranty.api;

import com.example.campusmarket.api.ApiErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;


/** Web-container parsing failures happen before a controller method is
 * selected, so they must be translated at the global MVC boundary. */
@RestControllerAdvice
public final class ApiProtocolExceptionHandler {
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<byte[]> malformedMultipart(MultipartException ignored) {
        return ApiErrors.bytes(HttpStatus.BAD_REQUEST, "请求参数无效");
    }
}
