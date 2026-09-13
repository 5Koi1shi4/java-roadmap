package com.example.campusmarket.catalog.api;

import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.catalog.application.ListingService;
import com.example.campusmarket.security.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Executable seller withdrawal command; the restriction check is inside its transaction. */
@RestController
@Profile("!test")
public final class SellerWithdrawalController {
    private static final MediaType JSON=MediaType.parseMediaType("application/json; charset=UTF-8");
    private final ListingService listings;
    public SellerWithdrawalController(ListingService listings){this.listings=listings;}
    @PostMapping(path="/api/seller/withdrawals", consumes=MediaType.APPLICATION_JSON_VALUE, produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> withdraw(@RequestBody Request request, @RequestHeader("Idempotency-Key") String key, Authentication auth){
        try {
            UUID seller=user(auth), id=listings.withdraw(seller,request.amountFen(),key);
            return ResponseEntity.accepted().contentType(JSON).body(("{\"withdrawalId\":\""+id+"\",\"status\":\"REQUESTED\"}").getBytes(StandardCharsets.UTF_8));
        } catch (ListingService.RestrictionException ex) { return error(409,"提现受限"); }
          catch (ListingService.InsufficientBalanceException ex) { return error(422,"可提现余额不足"); }
          catch (IllegalArgumentException ex) { return error(400,"提现请求无效"); }
          catch (IllegalStateException ex) { return error(503,"提现账户暂时不可用"); }
    }
    private static UUID user(Authentication auth){if(auth==null||!(auth.getPrincipal() instanceof AuthenticatedUser u))throw new IllegalArgumentException("身份无效");return u.userId();}
    private static ResponseEntity<byte[]> error(int status,String text){return ApiErrors.bytes(org.springframework.http.HttpStatus.valueOf(status), text);}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=false) public record Request(long amountFen) { }
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<byte[]> protocolError(Exception ignored){return error(400,"请求参数无效");}
}
