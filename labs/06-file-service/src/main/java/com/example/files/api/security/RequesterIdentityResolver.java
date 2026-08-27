package com.example.files.api.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/** 可替换的请求身份适配器端口。 */
@FunctionalInterface
public interface RequesterIdentityResolver {
    Optional<RequesterIdentity> resolve(HttpServletRequest request);
}
