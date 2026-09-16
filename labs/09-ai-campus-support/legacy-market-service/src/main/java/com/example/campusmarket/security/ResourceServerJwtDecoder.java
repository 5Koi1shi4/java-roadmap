package com.example.campusmarket.security;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Objects;

/** 将 JWKS 初始化/读取失败作为无效 Bearer Token 交给安全入口点处理。 */
final class ResourceServerJwtDecoder implements JwtDecoder {
    private final JwtDecoder delegate;

    ResourceServerJwtDecoder(JwtDecoder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "JwtDecoder 不能为空");
    }

    @Override
    public Jwt decode(String token) {
        try {
            return delegate.decode(token);
        } catch (BadJwtException invalidToken) {
            throw invalidToken;
        } catch (JwtDecoderInitializationException unavailable) {
            throw new BadJwtException("JWT decoder unavailable", unavailable);
        } catch (JwtException invalidToken) {
            throw new BadJwtException("JWT decode failed", invalidToken);
        }
    }
}
