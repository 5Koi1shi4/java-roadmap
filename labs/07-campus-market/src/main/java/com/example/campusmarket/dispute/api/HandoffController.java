package com.example.campusmarket.dispute.api;

import com.example.campusmarket.dispute.application.HandoffService;
import com.example.campusmarket.identity.application.AuthenticatedUser;
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

import java.nio.charset.StandardCharsets;
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
                                          @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        UUID seller = principal(authentication);
        HandoffService.Result result = service.handoff(orderId, seller, key, request == null ? "" : request.note());
        return response(result, result.success() ? HttpStatus.OK : HttpStatus.CONFLICT);
    }

    @PostMapping(path = "/{orderId}/receipt", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> receipt(@PathVariable UUID orderId, @RequestBody(required = false) ReceiptRequest ignored,
                                          @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        HandoffService.Result result = service.confirmReceipt(orderId, principal(authentication), key);
        return response(result, result.success() ? HttpStatus.OK : HttpStatus.CONFLICT);
    }

    private static UUID principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user))
            throw new IllegalStateException("用户身份无效");
        return user.userId();
    }

    private static ResponseEntity<byte[]> response(HandoffService.Result result, HttpStatus status) {
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(result.responseUtf8());
    }

    public record HandoffRequest(String note) {}
    public record ReceiptRequest() {}
}
