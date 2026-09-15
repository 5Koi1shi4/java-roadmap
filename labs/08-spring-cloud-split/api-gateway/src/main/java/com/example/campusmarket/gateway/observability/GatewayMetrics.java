package com.example.campusmarket.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gateway 低基数请求与依赖指标。 */
@Component
public final class GatewayMetrics implements WebFilter, Ordered {
    public static final String IDENTITY_ROUTE = "identity-api";
    public static final String LEGACY_ROUTE = "legacy-api";
    public static final String UNMATCHED_ROUTE = "unmatched";

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry 不能为空");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Timer.Sample sample = Timer.start(registry);
        String route = routeFor(exchange.getRequest().getPath().value());
        AtomicBoolean recorded = new AtomicBoolean();
        exchange.getResponse().beforeCommit(() -> {
            if (recorded.compareAndSet(false, true)) {
                recordRequest(exchange, route, sample, null);
            }
            return Mono.empty();
        });
        return chain.filter(exchange)
            .doOnSuccess(ignored -> {
                if (recorded.compareAndSet(false, true)) {
                    recordRequest(exchange, route, sample, null);
                }
            })
            .doOnError(error -> {
                if (recorded.compareAndSet(false, true)) {
                    recordRequest(exchange, route, sample, error);
                }
            });
    }

    public void recordJwksRefreshSuccess() {
        Counter.builder("gateway.jwks.refresh")
            .tag("result", "success")
            .register(registry)
            .increment();
    }

    public void recordJwksRefreshFailure() {
        Counter.builder("gateway.jwks.refresh")
            .tag("result", "failure")
            .register(registry)
            .increment();
    }

    public void recordUnknownKid() {
        Counter.builder("gateway.jwks.unknown_kid")
            .register(registry)
            .increment();
    }

    public void recordRouteUnavailable() {
        Counter.builder("gateway.route.unavailable")
            .register(registry)
            .increment();
    }

    public static String routeFor(String path) {
        if (path != null && (path.equals("/api/auth") || path.startsWith("/api/auth/"))) {
            return IDENTITY_ROUTE;
        }
        if (path != null && (path.equals("/api") || path.startsWith("/api/"))) {
            return LEGACY_ROUTE;
        }
        return UNMATCHED_ROUTE;
    }

    private void recordRequest(ServerWebExchange exchange, String route,
                               Timer.Sample sample, Throwable error) {
        int status = exchange.getResponse().getStatusCode() == null
            ? 500 : exchange.getResponse().getStatusCode().value();
        String result = resultFor(status, error);
        Counter.builder("campus.gateway.route")
            .tag("route", route)
            .tag("result", result)
            .register(registry)
            .increment();
        Counter.builder("gateway.requests")
            .tag("route", route)
            .tag("result", result)
            .register(registry)
            .increment();
        sample.stop(Timer.builder("gateway.request.duration")
            .tag("route", route)
            .register(registry));
        if (status == 503 || error != null) {
            recordRouteUnavailable();
        }
        if (status == 401 || status == 403 || status == 503) {
            Counter.builder("gateway.responses")
                .tag("route", route)
                .tag("status", Integer.toString(status))
                .register(registry)
                .increment();
        }
    }

    private static String resultFor(int status, Throwable error) {
        if (status == 401) {
            return "unauthorized";
        }
        if (status == 403) {
            return "forbidden";
        }
        if (status == 503 || error != null) {
            return "dependency_unavailable";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }
}
