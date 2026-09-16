package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.security.IdentityTokenIssuer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 从 Authorization Bearer 头读取并严格验证身份 JWT。 */
@Component
@Profile("!test")
@ConditionalOnBean(IdentityTokenIssuer.class)
public final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final IdentityTokenIssuer tokenIssuer;

    public JwtAuthenticationFilter(IdentityTokenIssuer tokenIssuer) {
        this.tokenIssuer = java.util.Objects.requireNonNull(tokenIssuer, "令牌签发器不能为空");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
            && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedUser user = tokenIssuer.authenticate(header.substring(7).trim());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    user, null, user.roles().stream().map(SimpleGrantedAuthority::new).toList());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                // 非法、过期或格式错误的 token 只按未认证继续处理。
            }
        }
        filterChain.doFilter(request, response);
    }
}
