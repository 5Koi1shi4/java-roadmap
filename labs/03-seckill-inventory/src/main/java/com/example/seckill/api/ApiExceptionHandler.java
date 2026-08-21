package com.example.seckill.api;

import com.example.seckill.application.AlreadyPurchasedException;
import com.example.seckill.application.ProductNotFoundException;
import com.example.seckill.application.SoldOutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> productNotFound(ProductNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ApiError> soldOut(SoldOutException exception) {
        return error(HttpStatus.CONFLICT, "SOLD_OUT", exception.getMessage());
    }

    @ExceptionHandler(AlreadyPurchasedException.class)
    public ResponseEntity<ApiError> alreadyPurchased(AlreadyPurchasedException exception) {
        return error(HttpStatus.CONFLICT, "ALREADY_PURCHASED", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
