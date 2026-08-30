package com.example.campusmarket.identity.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JsonContentTypeFilter extends OncePerRequestFilter {
    private static final String JSON_UTF8 = "application/json; charset=UTF-8";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpServletResponseWrapper wrapped = new HttpServletResponseWrapper(response) {
            @Override
            public void setContentType(String type) {
                super.setContentType(jsonType(type));
            }

            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, "Content-Type".equalsIgnoreCase(name) ? jsonType(value) : value);
            }

            @Override
            public void addHeader(String name, String value) {
                super.addHeader(name, "Content-Type".equalsIgnoreCase(name) ? jsonType(value) : value);
            }
        };
        filterChain.doFilter(request, wrapped);
        if (!response.isCommitted()) {
            response.setHeader("Content-Type", JSON_UTF8);
        }
    }

    private static String jsonType(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")
            ? JSON_UTF8 : value;
    }
}
