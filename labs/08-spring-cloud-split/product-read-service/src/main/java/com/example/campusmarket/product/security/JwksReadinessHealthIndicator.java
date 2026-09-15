package com.example.campusmarket.product.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.client.RestOperations;

import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** 独立 JWKS readiness 探针；成功预热后允许短暂身份服务中断。 */
public final class JwksReadinessHealthIndicator implements HealthIndicator {
    private final RestOperations restOperations;
    private final String jwksUri;
    private final Clock clock;
    private final Duration staleWindow;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();

    public JwksReadinessHealthIndicator(RestOperations restOperations, String jwksUri) {
        this(restOperations, jwksUri, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    JwksReadinessHealthIndicator(RestOperations restOperations, String jwksUri,
                                 Clock clock, Duration staleWindow) {
        this.restOperations = Objects.requireNonNull(restOperations, "JWKS RestOperations 不能为空");
        this.jwksUri = Objects.requireNonNull(jwksUri, "JWKS URI 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.staleWindow = Objects.requireNonNull(staleWindow, "陈旧窗口不能为空");
        if (staleWindow.isNegative() || staleWindow.isZero()) {
            throw new IllegalArgumentException("陈旧窗口必须为正数");
        }
    }

    @Override
    public Health health() {
        try {
            String body = restOperations.getForObject(jwksUri, String.class);
            JWKSet set = JWKSet.parse(Objects.requireNonNull(body, "JWKS 响应为空"));
            boolean usable = set.getKeys().stream().anyMatch(key ->
                key instanceof RSAKey rsa && !rsa.isPrivate()
                    && rsa.getKeyID() != null && !rsa.getKeyID().isBlank()
                    && KeyUse.SIGNATURE.equals(rsa.getKeyUse())
                    && JWSAlgorithm.RS256.equals(rsa.getAlgorithm()));
            if (!usable) {
                throw new IllegalStateException("JWKS 没有可用 RS256 公钥");
            }
            lastSuccess.set(clock.instant());
            return Health.up().build();
        } catch (Exception ignored) {
            Instant previous = lastSuccess.get();
            Instant now = clock.instant();
            return previous != null && !now.isBefore(previous)
                && now.isBefore(previous.plus(staleWindow))
                ? Health.up().build() : Health.down().build();
        }
    }
}
