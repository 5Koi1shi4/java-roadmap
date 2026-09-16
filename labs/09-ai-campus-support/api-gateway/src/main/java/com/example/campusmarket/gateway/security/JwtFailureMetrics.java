package com.example.campusmarket.gateway.security;

import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 记录 Gateway JWT 结果，同时保持 Nimbus 自带的未知 kid 单次刷新语义。
 *
 * <p>此装饰器只读取紧凑 JWT 的 header，签名和 claims 仍完全交给 Nimbus 验证；它不再对
 * delegate 进行重试，因此也不会把 Nimbus 的一次刷新变成递归刷新。</p>
 */
public final class JwtFailureMetrics implements ReactiveJwtDecoder {
    private static final String METRIC = "campus.gateway.jwt";

    private final ReactiveJwtDecoder delegate;
    private final MeterRegistry registry;

    public JwtFailureMetrics(ReactiveJwtDecoder delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "JWT decoder 不能为空");
        this.registry = Objects.requireNonNull(registry, "MeterRegistry 不能为空");
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return Mono.defer(() -> {
            Header header = Header.parse(token);
            try {
                return delegate.decode(token)
                    .doOnSuccess(ignored -> increment("valid"))
                    .doOnError(error -> increment(classify(header, error)))
                    .onErrorMap(this::needsAuthenticationFailure,
                        error -> new BadJwtException("JWT decode failed", error));
            } catch (RuntimeException error) {
                increment(classify(header, error));
                return Mono.error(asAuthenticationFailure(error));
            }
        });
    }

    private boolean needsAuthenticationFailure(Throwable error) {
        return !(error instanceof BadJwtException);
    }

    private static RuntimeException asAuthenticationFailure(RuntimeException error) {
        return error instanceof BadJwtException
            ? error : new BadJwtException("JWT decode failed", error);
    }

    private String classify(Header header, Throwable error) {
        if (header == null || !"RS256".equals(header.algorithm()) || header.kid() == null
            || header.kid().isBlank()) {
            return "invalid";
        }
        // Nimbus reports a missing matching key as BadJOSEException. A bad signature is
        // specifically BadJWSException and must remain an invalid-token result.
        return hasCause(error, BadJOSEException.class) && !hasCause(error, BadJWSException.class)
            ? "unknown_kid" : "invalid";
    }

    private void increment(String result) {
        Counter.builder(METRIC).tag("result", result).register(registry).increment();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private record Header(String algorithm, String kid) {
        private static Header parse(String token) {
            try {
                var header = SignedJWT.parse(token).getHeader();
                return new Header(header.getAlgorithm().getName(), header.getKeyID());
            } catch (RuntimeException | java.text.ParseException ignored) {
                return null;
            }
        }
    }
}
