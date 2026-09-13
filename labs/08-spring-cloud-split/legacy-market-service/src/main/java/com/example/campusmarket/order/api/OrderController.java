package com.example.campusmarket.order.api;

import com.example.campusmarket.security.AuthenticatedUser;
import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.order.application.CreateOrderCommand;
import com.example.campusmarket.order.application.CreateOrderService;
import com.example.campusmarket.order.application.IdempotentCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Objects;

@RestController
@Profile("!test")
@RequestMapping(path = "/api/orders", produces = "application/json; charset=UTF-8")
public class OrderController {
    private final CreateOrderService service;

    public OrderController(CreateOrderService service) {
        this.service = Objects.requireNonNull(service, "订单服务不能为空");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public void create(@RequestBody CreateRequest request,
                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                       Authentication authentication, HttpServletResponse response) throws IOException {
        UUID buyerId = ((AuthenticatedUser) authentication.getPrincipal()).userId();
        var result = service.create(buyerId, idempotencyKey, new CreateOrderCommand(request.listingId(), request.quantity()));
        write(response, result.statusCode(), result.responseUtf8());
    }

    @ExceptionHandler(CreateOrderService.SelfPurchaseException.class)
    void selfPurchase(CreateOrderService.SelfPurchaseException exception, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.UNPROCESSABLE_ENTITY, "不能购买自己的商品");
    }

    @ExceptionHandler({CreateOrderService.StockConflictException.class, IdempotentCommandService.IdempotencyConflictException.class})
    void conflict(RuntimeException exception, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.CONFLICT, "商品库存或幂等请求冲突");
    }

    @ExceptionHandler(CreateOrderService.ListingNotFoundException.class)
    void notFound(CreateOrderService.ListingNotFoundException exception, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.NOT_FOUND, "商品不存在");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    void invalidRequest(IllegalArgumentException exception, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.BAD_REQUEST, "请求参数无效");
    }

    @ExceptionHandler(IllegalStateException.class)
    void internalFailure(IllegalStateException exception, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, "订单创建失败");
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        write(response, status.value(), ApiErrors.body(status, message));
    }

    private static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Type", "application/json; charset=UTF-8");
        response.getOutputStream().write(body);
    }

    public record CreateRequest(UUID listingId, int quantity) { }
}
