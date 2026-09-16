package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.security.IdentityTokenIssuer;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 对外发布身份服务当前签名公钥的 JWKS 端点。 */
@RestController
@RequestMapping(path = "/api/auth", produces = "application/json; charset=UTF-8")
public final class JwksController {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");
    private final IdentityTokenIssuer tokenIssuer;

    public JwksController(IdentityTokenIssuer tokenIssuer) {
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "令牌签发器不能为空");
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> jwks() {
        RSAKey publicJwk = tokenIssuer.publicJwk();
        @SuppressWarnings("unchecked")
        Map<String, Object> key = (Map<String, Object>) (Map<?, ?>) publicJwk.toJSONObject();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, JSON_UTF8.toString())
            .body(Map.of("keys", List.of(key)));
    }
}
