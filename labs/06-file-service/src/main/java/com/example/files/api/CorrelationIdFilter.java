package com.example.files.api;

import com.example.files.application.audit.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;

/** 每次请求生成独立内部关联 ID，客户端追踪值只作为可选独立属性保留。 */
@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String CORRELATION_ID_ATTRIBUTE = "file.correlationId";
    public static final String CORRELATION_ID_OBJECT_ATTRIBUTE = "file.correlationIdObject";
    public static final String CLIENT_TRACE_ID_ATTRIBUTE = "file.clientTraceId";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final String CLIENT_TRACE_HEADER = "X-Client-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CorrelationId correlationId = CorrelationId.random();
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId.value());
        request.setAttribute(CORRELATION_ID_OBJECT_ATTRIBUTE, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId.value());
        String clientTrace = singleHeader(request, CLIENT_TRACE_HEADER);
        if (clientTrace != null && clientTrace.matches("[A-Za-z0-9._:-]{1,128}")) {
            request.setAttribute(CLIENT_TRACE_ID_ATTRIBUTE, clientTrace);
        }
        try {
            MDC.put(MDC_KEY, correlationId.value());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }
}
