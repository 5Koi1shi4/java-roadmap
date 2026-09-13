package com.example.campusmarket.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 将资源服务器已验签的 JWT 严格映射为市场用户主体和角色。 */
public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (jwt == null) {
            throw new JwtException("JWT 不能为空");
        }
        UUID userId = parseUserId(jwt.getSubject());
        List<String> rawRoles;
        try {
            rawRoles = jwt.getClaimAsStringList("roles");
        } catch (RuntimeException ex) {
            throw new JwtException("JWT 角色非法");
        }
        if (rawRoles == null || rawRoles.isEmpty()
            || rawRoles.stream().anyMatch(role -> role == null || role.isBlank()
                || !ALLOWED_ROLES.contains(role))) {
            throw new JwtException("JWT 角色非法");
        }
        Set<String> roles = Set.copyOf(rawRoles);
        List<GrantedAuthority> authorities = roles.stream()
            .map(SimpleGrantedAuthority::new)
            .map(GrantedAuthority.class::cast)
            .toList();
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(userId, roles), jwt.getTokenValue(), authorities);
    }

    private static UUID parseUserId(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new JwtException("JWT 用户 ID 非法");
        }
        try {
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equalsIgnoreCase(subject)) {
                throw new JwtException("JWT 用户 ID 非法");
            }
            return userId;
        } catch (IllegalArgumentException ex) {
            throw new JwtException("JWT 用户 ID 非法");
        }
    }
}
