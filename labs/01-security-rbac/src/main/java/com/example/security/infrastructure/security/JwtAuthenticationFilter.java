package com.example.security.infrastructure.security;

import com.example.security.application.InvalidTokenException;
import com.example.security.application.RbacService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokens;
    private final RbacService rbac;

    public JwtAuthenticationFilter(JwtTokenService tokens, RbacService rbac) {
        this.tokens = tokens;
        this.rbac = rbac;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            authenticate(authorization.substring(BEARER_PREFIX.length()));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            JwtTokenService.AccessPrincipal principal = tokens.verifyAccessToken(token);
            List<SimpleGrantedAuthority> authorities = rbac.authoritiesOf(principal.userId()).stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    authorities
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidTokenException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
