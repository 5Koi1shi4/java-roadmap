package com.example.campusmarket.identity.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Keeps the wire-level JSON media type exact, including the required charset separator. */
@ControllerAdvice
public class JsonUtf8ResponseAdvice implements ResponseBodyAdvice<Object> {
    private static final String JSON_UTF8 = "application/json; charset=UTF-8";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().set("Content-Type", JSON_UTF8);
        return body;
    }
}
