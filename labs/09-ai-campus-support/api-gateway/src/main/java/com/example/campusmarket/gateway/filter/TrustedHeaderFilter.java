package com.example.campusmarket.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 清洗来自客户端的内部身份、来源和关联 Header。 */
@Component
public final class TrustedHeaderFilter implements GlobalFilter, WebFilter, Ordered {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ATTRIBUTE = TrustedHeaderFilter.class.getName() + ".correlationId";

    /**
     * 直接清洗一个请求，供单元测试和其他边界适配代码使用。
     *
     * @param request 原始请求
     * @return 删除不可信 Header 并添加新关联 ID 后的请求
     */
    public ServerHttpRequest filter(ServerHttpRequest request) {
        Objects.requireNonNull(request, "请求不能为空");
        return sanitize(request, UUID.randomUUID().toString());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Objects.requireNonNull(exchange, "exchange 不能为空");
        Objects.requireNonNull(chain, "WebFilterChain 不能为空");
        String correlationId = correlationId(exchange);
        ServerWebExchange sanitized = sanitizedExchange(exchange, correlationId);
        return chain.filter(sanitized);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Objects.requireNonNull(exchange, "exchange 不能为空");
        Objects.requireNonNull(chain, "GatewayFilterChain 不能为空");
        String correlationId = correlationId(exchange);
        ServerWebExchange sanitized = sanitizedExchange(exchange, correlationId);
        return chain.filter(sanitized);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static ServerWebExchange sanitizedExchange(ServerWebExchange exchange, String correlationId) {
        exchange.getAttributes().put(CORRELATION_ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);
        return exchange.mutate().request(sanitize(exchange.getRequest(), correlationId)).build();
    }

    private static String correlationId(ServerWebExchange exchange) {
        String existing = exchange.getAttribute(CORRELATION_ATTRIBUTE);
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    private static ServerHttpRequest sanitize(ServerHttpRequest request, String correlationId) {
        return request.mutate().headers(headers -> {
            headers.headerNames().stream()
                .filter(TrustedHeaderFilter::isUntrusted)
                .toList()
                .forEach(headers::remove);
            headers.set(CORRELATION_HEADER, correlationId);
        }).build();
    }

    private static boolean isUntrusted(String headerName) {
        String lower = headerName.toLowerCase(Locale.ROOT);
        return lower.equals("x-user-id")
            || lower.equals("x-user-roles")
            || lower.equals("x-authenticated-user")
            || lower.startsWith("x-internal-")
            || lower.equals("x-correlation-id")
            || lower.equals("forwarded")
            || lower.startsWith("x-forwarded-")
            || lower.equals("x-real-ip")
            || lower.equals("client-ip")
            || lower.equals("true-client-ip")
            || lower.equals("cf-connecting-ip")
            || lower.startsWith("x-device-");
    }
}
