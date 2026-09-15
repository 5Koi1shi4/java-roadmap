package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/** 身份服务无状态安全链，认证由 RS256 JWT 过滤器完成。 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnBean(JwtAuthenticationFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/**", "/actuator/health",
                    "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    writeJsonError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证"))
                .accessDeniedHandler((request, response, exception) ->
                    writeJsonError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问")))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    private static void writeJsonError(HttpServletResponse response, HttpStatus status,
                                       String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json; charset=UTF-8");
        String json = "{\"code\":\"" + code + "\",\"message\":\"" + message
            + "\",\"correlationId\":\"" + ApiErrors.correlationId() + "\"}";
        response.getOutputStream().write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
