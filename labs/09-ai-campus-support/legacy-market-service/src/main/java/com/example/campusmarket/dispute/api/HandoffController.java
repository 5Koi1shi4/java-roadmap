package com.example.campusmarket.dispute.api;

import com.example.campusmarket.dispute.application.HandoffService;
import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.security.AuthenticatedUser;
import com.example.campusmarket.order.application.IdempotentCommandService;
import com.example.campusmarket.order.application.OrderLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;

import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping(path = "/api/orders", produces = "application/json; charset=UTF-8")
public final class HandoffController {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");
    private final HandoffService service;

    public HandoffController(HandoffService service) { this.service = Objects.requireNonNull(service, "交付服务不能为空"); }

    @PostMapping(path = "/{orderId}/handoff", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> handoff(@PathVariable UUID orderId, @RequestBody(required = false) HandoffRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        if (!validKey(key)) return error(HttpStatus.BAD_REQUEST, "幂等参数无效");
        UUID seller = principal(authentication);
        try {
            HandoffService.Result result = service.handoff(orderId, seller, key, request == null ? "" : request.note());
            return response(result, result.success() ? HttpStatus.OK : HttpStatus.CONFLICT);
        } catch (IdempotentCommandService.IdempotencyConflictException e) { return idempotencyConflict(); }
          catch (OrderLifecycleService.OrderNotFoundException e) { return notFound(); }
          catch (OrderLifecycleService.ForbiddenParticipantException e) { return forbidden(); }
          catch (DataAccessException | org.springframework.transaction.TransactionException e) { return unavailable(); }
          catch (IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    }

    @PostMapping(path = "/{orderId}/receipt", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> receipt(@PathVariable UUID orderId, @RequestBody(required = false) ReceiptRequest ignored,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        if (!validKey(key)) return error(HttpStatus.BAD_REQUEST, "幂等参数无效");
        try {
            HandoffService.Result result = service.confirmReceipt(orderId, principal(authentication), key);
            return response(result, result.success() ? HttpStatus.OK : HttpStatus.CONFLICT);
        } catch (IdempotentCommandService.IdempotencyConflictException e) { return idempotencyConflict(); }
          catch (OrderLifecycleService.OrderNotFoundException e) { return notFound(); }
          catch (OrderLifecycleService.ForbiddenParticipantException e) { return forbidden(); }
          catch (DataAccessException | org.springframework.transaction.TransactionException e) { return unavailable(); }
          catch (IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    }

    private static UUID principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user))
            throw new IllegalStateException("用户身份无效");
        return user.userId();
    }

    private static ResponseEntity<byte[]> response(HandoffService.Result result, HttpStatus status) {
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(result.responseUtf8());
    }

    @ExceptionHandler(IdempotentCommandService.IdempotencyConflictException.class)
    public ResponseEntity<byte[]> idempotencyConflict() { return error(HttpStatus.CONFLICT, "幂等请求冲突"); }

    @ExceptionHandler(OrderLifecycleService.OrderNotFoundException.class)
    public ResponseEntity<byte[]> notFound() { return error(HttpStatus.NOT_FOUND, "订单不存在"); }

    public ResponseEntity<byte[]> forbidden() { return error(HttpStatus.FORBIDDEN, "无权执行该角色操作"); }

    public ResponseEntity<byte[]> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "服务暂不可用"); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<byte[]> malformedRequest() { return error(HttpStatus.BAD_REQUEST, "请求格式无效"); }

    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public ResponseEntity<byte[]> infrastructureFailure() { return unavailable(); }

    private static boolean validKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 191 && key.chars().noneMatch(Character::isISOControl);
    }

    private static ResponseEntity<byte[]> error(HttpStatus status, String message) {
        return ApiErrors.bytes(status, message);
    }

    public record HandoffRequest(String note) {}
    public record ReceiptRequest() {}
}
