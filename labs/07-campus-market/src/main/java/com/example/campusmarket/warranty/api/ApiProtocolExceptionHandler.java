package com.example.campusmarket.warranty.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.nio.charset.StandardCharsets;

/** Web-container parsing failures happen before a controller method is
 * selected, so they must be translated at the global MVC boundary. */
@RestControllerAdvice
public final class ApiProtocolExceptionHandler {
    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<byte[]> malformedMultipart(MultipartException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(JSON)
            .body("{\"error\":\"请求参数无效\"}".getBytes(StandardCharsets.UTF_8));
    }
}
