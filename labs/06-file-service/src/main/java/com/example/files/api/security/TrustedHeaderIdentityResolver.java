package com.example.files.api.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;
import java.util.Optional;

/** 仅供受控 local/test 环境使用的可信身份 Header 适配器。 */
public final class TrustedHeaderIdentityResolver implements RequesterIdentityResolver {
    public static final String HEADER_NAME = "X-Trusted-User-Id";

    @Override
    public Optional<RequesterIdentity> resolve(HttpServletRequest request) {
        if (request == null) return Optional.empty();
        Enumeration<String> values = request.getHeaders(HEADER_NAME);
        if (values == null || !values.hasMoreElements()) return Optional.empty();
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || !value.matches("[1-9][0-9]*")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RequesterIdentity(Long.parseLong(value)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
