package com.example.seckill.api;

import com.example.seckill.application.SeckillOrderService;
import com.example.seckill.application.CreateOrderCommand;
import com.example.seckill.application.IdempotentOrderResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seckill/orders")
public class SeckillOrderController {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final SeckillOrderService service;

    public SeckillOrderController(SeckillOrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> placeOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSeckillOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 请求头不能为空");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 请求头长度不能超过 128 个字符");
        }

        CreateOrderCommand command = new CreateOrderCommand(request.userId(), request.productId());
        IdempotentOrderResult result = service.placeOrder(idempotencyKey, command);
        return ResponseEntity.status(result.httpStatus())
                .contentType(JSON_UTF8)
                .body(result.responseBody());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(new ApiError(code, message));
    }
}
