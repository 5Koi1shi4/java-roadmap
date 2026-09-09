package com.example.campusmarket.payment.api;

import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.payment.application.SettlementService;
import com.example.campusmarket.order.application.IdempotentCommandService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Seller-facing settlement command.  Settlement remains behind the same authenticated HTTP boundary as other order commands. */
@RestController
@Profile("!test")
@RequestMapping(produces = "application/json; charset=UTF-8")
public final class SettlementController {
    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");
    private final SettlementService settlements;
    private final JdbcTemplate jdbc;
    private final IdempotentCommandService commands;

    public SettlementController(SettlementService settlements, JdbcTemplate jdbc, IdempotentCommandService commands) {
        this.settlements = settlements;
        this.jdbc = jdbc;
        this.commands = commands;
    }

    @PostMapping(path = "/api/orders/{orderId}/settlement")
    public ResponseEntity<byte[]> settle(@PathVariable UUID orderId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         Authentication authentication) {
        UUID user = authenticatedUser(authentication);
        if (key == null || key.isBlank()) return error(HttpStatus.BAD_REQUEST, "幂等键不能为空");
        UUID seller = jdbc.query("SELECT seller_id FROM trade_order WHERE id=?", rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            orderId.toString());
        if (seller == null) return error(HttpStatus.NOT_FOUND, "订单不存在");
        if (!seller.equals(user) && !authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
            return error(HttpStatus.FORBIDDEN, "无权执行该订单操作");
        byte[] response = commands.executeLifecycle(user, key, "ORDER_SETTLEMENT", orderId, new byte[0], orderId,
            () -> {
                SettlementService.SettlementResult result = settlements.settle(orderId);
                return encode(result);
            });
        return response != null && new String(response, StandardCharsets.UTF_8).contains("\"status\":\"BLOCKED\"")
            ? ResponseEntity.status(HttpStatus.CONFLICT).contentType(JSON).body(response)
            : ResponseEntity.ok().contentType(JSON).body(response);
    }

    private static UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user))
            throw new IllegalArgumentException("身份无效");
        return user.userId();
    }

    private static byte[] encode(SettlementService.SettlementResult result) {
        String reason = result.blockedReason() == null ? "null" : "\"" + result.blockedReason() + "\"";
        String json = "{\"orderId\":\"" + result.orderId() + "\",\"status\":\"" + result.status()
            + "\",\"netSettlementFen\":" + result.netSettlementFen() + ",\"blockedReason\":" + reason + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static ResponseEntity<byte[]> error(HttpStatus status, String message) {
        return ApiErrors.bytes(status, message);
    }
}
