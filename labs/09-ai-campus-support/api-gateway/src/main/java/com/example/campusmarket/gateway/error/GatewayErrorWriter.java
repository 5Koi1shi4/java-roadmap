package com.example.campusmarket.gateway.error;

import com.example.campusmarket.gateway.filter.TrustedHeaderFilter;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.reactivestreams.Publisher;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Gateway 边界错误统一写出器，不透传下游异常体。 */
@Component
@Order(-2)
public final class GatewayErrorWriter implements ErrorWebExceptionHandler, GlobalFilter, Ordered {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");
    private static final String DEPENDENCY_CODE = "DEPENDENCY_UNAVAILABLE";
    private static final String DEPENDENCY_MESSAGE = "依赖服务暂时不可用";

    /**
     * 以稳定 JSON 结构直接写出边界错误。
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                            String code, String message) {
        Objects.requireNonNull(exchange, "exchange 不能为空");
        Objects.requireNonNull(status, "状态码不能为空");
        Objects.requireNonNull(code, "错误代码不能为空");
        Objects.requireNonNull(message, "错误消息不能为空");
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        String correlationId = correlationId(exchange);
        String safeCode = codeFor(status);
        String safeMessage = messageFor(status);
        byte[] bytes = payload(safeCode, safeMessage, correlationId);
        response.setStatusCode(status);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, JSON_UTF8.toString());
        response.getHeaders().set(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length));
        response.getHeaders().set(TrustedHeaderFilter.CORRELATION_HEADER, correlationId);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /** 将路由代理期间发生的、尚未提交响应的依赖异常转换为稳定 503。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange decoratedExchange = exchange.mutate()
            .response(new SanitizingResponse(exchange.getResponse(), exchange))
            .build();
        return chain.filter(decoratedExchange)
            .onErrorResume(throwable -> exchange.getResponse().isCommitted()
                ? Mono.error(throwable)
                : write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    DEPENDENCY_CODE, DEPENDENCY_MESSAGE));
    }

    @Override
    public int getOrder() {
        return -2;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable throwable) {
        if (throwable instanceof ResponseStatusException responseStatusException
            && responseStatusException.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.resolve(responseStatusException.getStatusCode().value());
            if (status != null) {
                return write(exchange, status, codeFor(status), messageFor(status));
            }
        }
        return write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
            DEPENDENCY_CODE, DEPENDENCY_MESSAGE);
    }

    private static String correlationId(ServerWebExchange exchange) {
        String value = exchange.getAttribute(TrustedHeaderFilter.CORRELATION_ATTRIBUTE);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String codeFor(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 503 -> DEPENDENCY_CODE;
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }

    private static String messageFor(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "请求参数无效";
            case 401 -> "未认证";
            case 403 -> "无权访问";
            case 404 -> "资源不存在";
            case 409 -> "请求与当前状态冲突";
            case 429 -> "请求过于频繁";
            case 503 -> DEPENDENCY_MESSAGE;
            default -> status.is5xxServerError() ? "服务暂时不可用" : "请求失败";
        };
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** 在下游已返回 5xx 时丢弃其响应体，避免内部异常信息穿透到客户端。 */
    private final class SanitizingResponse extends ServerHttpResponseDecorator {
        private final ServerWebExchange exchange;
        private boolean safeResponseWritten;

        private SanitizingResponse(ServerHttpResponse delegate, ServerWebExchange exchange) {
            super(delegate);
            this.exchange = exchange;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            if (!isServerError()) {
                return super.writeWith(body);
            }
            return Flux.from(body)
                .doOnNext(buffer -> DataBufferUtils.release(buffer))
                .then(writeSafeResponse());
        }

        @Override
        public Mono<Void> writeAndFlushWith(
            Publisher<? extends Publisher<? extends DataBuffer>> body) {
            if (!isServerError()) {
                return super.writeAndFlushWith(body);
            }
            return Flux.from(body)
                .concatMap(Flux::from)
                .doOnNext(buffer -> DataBufferUtils.release(buffer))
                .then(writeSafeResponse());
        }

        @Override
        public Mono<Void> setComplete() {
            return isServerError() ? writeSafeResponse() : super.setComplete();
        }

        private boolean isServerError() {
            HttpStatusCode status = getStatusCode();
            return status != null && status.is5xxServerError();
        }

        private Mono<Void> writeSafeResponse() {
            if (safeResponseWritten) {
                return Mono.empty();
            }
            safeResponseWritten = true;
            String correlationId = correlationId(exchange);
            byte[] bytes = payload(DEPENDENCY_CODE, DEPENDENCY_MESSAGE, correlationId);
            getHeaders().clear();
            setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            getHeaders().set(HttpHeaders.CONTENT_TYPE, JSON_UTF8.toString());
            getHeaders().set(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length));
            getHeaders().set(TrustedHeaderFilter.CORRELATION_HEADER, correlationId);
            return getDelegate().writeWith(Mono.just(bufferFactory().wrap(bytes)));
        }
    }

    private static byte[] payload(String code, String message, String correlationId) {
        return ("{\"code\":\"" + escape(code)
            + "\",\"message\":\"" + escape(message)
            + "\",\"correlationId\":\"" + escape(correlationId) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    }
}
