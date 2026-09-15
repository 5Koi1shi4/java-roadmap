package com.example.campusmarket.gateway.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** 独立 JWKS readiness 探针；成功预热后允许短暂身份服务中断。 */
public final class JwksReadinessHealthIndicator implements ReactiveHealthIndicator {
    private final WebClient client;
    private final String jwksUri;
    private final Clock clock;
    private final Duration staleWindow;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();

    public JwksReadinessHealthIndicator(WebClient client, String jwksUri) {
        this(client, jwksUri, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    JwksReadinessHealthIndicator(WebClient client, String jwksUri, Clock clock,
                                 Duration staleWindow) {
        this.client = Objects.requireNonNull(client, "JWKS WebClient 不能为空");
        this.jwksUri = Objects.requireNonNull(jwksUri, "JWKS URI 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.staleWindow = Objects.requireNonNull(staleWindow, "陈旧窗口不能为空");
        if (staleWindow.isNegative() || staleWindow.isZero()) {
            throw new IllegalArgumentException("陈旧窗口必须为正数");
        }
    }

    @Override
    public Mono<Health> health() {
        return client.get().uri(jwksUri)
            .retrieve()
            .bodyToMono(String.class)
            .flatMap(this::parse)
            .map(ignored -> {
                lastSuccess.set(clock.instant());
                return Health.up().build();
            })
            .switchIfEmpty(Mono.fromSupplier(this::notPrimed))
            .onErrorResume(ignored -> Mono.just(notPrimed()));
    }

    private Mono<JWKSet> parse(String body) {
        return Mono.fromCallable(() -> {
            JWKSet set = JWKSet.parse(body);
            boolean usable = set.getKeys().stream().anyMatch(key ->
                key instanceof RSAKey rsa && !rsa.isPrivate()
                    && rsa.getKeyID() != null && !rsa.getKeyID().isBlank()
                    && KeyUse.SIGNATURE.equals(rsa.getKeyUse())
                    && JWSAlgorithm.RS256.equals(rsa.getAlgorithm()));
            if (!usable) {
                throw new IllegalStateException("JWKS 没有可用 RS256 公钥");
            }
            return set;
        });
    }

    private Health notPrimed() {
        Instant previous = lastSuccess.get();
        Instant now = clock.instant();
        return previous != null && !now.isBefore(previous)
            && now.isBefore(previous.plus(staleWindow))
            ? Health.up().build() : Health.down().build();
    }
}
