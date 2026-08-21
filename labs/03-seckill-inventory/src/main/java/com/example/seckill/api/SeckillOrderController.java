package com.example.seckill.api;

import com.example.seckill.application.SeckillOrderService;
import com.example.seckill.application.IdempotentOrderResult;
import com.example.seckill.application.CreateOrderCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ObjectMapper objectMapper;

    public SeckillOrderController(SeckillOrderService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> placeOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode requestBody) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 请求头不能为空");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 请求头长度不能超过 128 个字符");
        }

        CreateSeckillOrderRequest request;
        try {
            request = objectMapper.treeToValue(requestBody, CreateSeckillOrderRequest.class);
        } catch (JsonProcessingException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败");
        }
        if (request.userId() == null || request.userId() <= 0
                || request.productId() == null || request.productId() <= 0) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败");
        }
        String normalizedRequestBody;
        try {
            normalizedRequestBody = objectMapper.writeValueAsString(canonicalize(requestBody));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法规范化请求 JSON", exception);
        }
        CreateOrderCommand command = new CreateOrderCommand(request.userId(), request.productId(), normalizedRequestBody);
        IdempotentOrderResult result = service.placeOrder(idempotencyKey, command);
        return ResponseEntity.status(result.httpStatus())
                .contentType(JSON_UTF8)
                .body(result.responseBody());
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            java.util.Iterator<String> fields = node.fieldNames();
            fields.forEachRemaining(name -> sorted.set(name, canonicalize(node.get(name))));
            java.util.List<String> names = new java.util.ArrayList<>();
            sorted.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            names.forEach(name -> result.set(name, sorted.get(name)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node;
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(new ApiError(code, message));
    }
}
