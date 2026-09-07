package com.example.campusmarket.warranty.api;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.shared.Money;
import com.example.campusmarket.warranty.application.SellerObligationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController @Profile("!test")
public final class SellerObligationController {
    private final SellerObligationService obligations; public SellerObligationController(SellerObligationService obligations){this.obligations=obligations;}
    @PostMapping(path={"/api/seller/obligations/{obligationId}/fund","/api/seller-obligations/{obligationId}/fund"},consumes=MediaType.APPLICATION_JSON_VALUE,produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> fund(@PathVariable UUID obligationId,@RequestBody FundRequest req,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,Authentication auth){try{UUID seller=user(auth);String key=idempotencyKey==null||idempotencyKey.isBlank()?"legacy-"+obligationId+"-"+req.amountFen():idempotencyKey;var r=obligations.fundObligation(obligationId,seller,Money.ofFen(req.amountFen()),key);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/json; charset=UTF-8")).body(("{\"obligationId\":\""+r.obligationId()+"\",\"fundedAmountFen\":"+r.fundedAmountFen()+",\"status\":\""+r.status()+"\"}").getBytes(StandardCharsets.UTF_8));}catch(SellerObligationService.NotFoundException e){return ResponseEntity.status(404).body(new byte[0]);}catch(RuntimeException e){return ResponseEntity.status(409).body(new byte[0]);}}
    @GetMapping({"/api/seller/restrictions","/api/seller-obligations/restrictions"}) public ResponseEntity<byte[]> restrictions(Authentication auth){UUID seller=user(auth);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/json; charset=UTF-8")).body(("{\"publishRestricted\":"+obligations.isRestricted(seller,"PUBLISH")+",\"withdrawRestricted\":"+obligations.isRestricted(seller,"WITHDRAW")+"}").getBytes(StandardCharsets.UTF_8));}
    @GetMapping({"/api/seller/obligations","/api/seller-obligations"}) public ResponseEntity<byte[]> list(Authentication auth){StringBuilder body=new StringBuilder("[");boolean first=true;for(var row:obligations.findForSeller(user(auth))){if(!first)body.append(',');first=false;body.append("{\"obligationId\":\"").append(row.obligationId()).append("\",\"warrantyCaseId\":\"").append(row.warrantyCaseId()).append("\",\"amountFen\":").append(row.obligationAmountFen()).append(",\"fundedAmountFen\":").append(row.fundedAmountFen()).append(",\"status\":\"").append(row.status()).append("\"}");}body.append(']');return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/json; charset=UTF-8")).body(body.toString().getBytes(StandardCharsets.UTF_8));}
    private static UUID user(Authentication a){if(a==null||!(a.getPrincipal() instanceof AuthenticatedUser u))throw new IllegalArgumentException("身份无效");return u.userId();} @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=false) public record FundRequest(long amountFen){}
}
