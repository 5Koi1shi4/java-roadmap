package com.example.campusmarket.supportai.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** AI 服务独立验证身份服务签发的 RS256 JWT，不信任 Gateway 注入的身份。 */
@Configuration(proxyBeanMethods = false)
public class SupportSecurityConfiguration {
    private static final String REQUIRED_AUDIENCE = "campus-market-api";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    @Bean
    JwtDecoder supportJwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${campus.market.jwt.audience:campus-market-api}") String audience) {
        if (jwkSetUri == null || jwkSetUri.isBlank() || issuer == null || issuer.isBlank()
            || !REQUIRED_AUDIENCE.equals(audience)) {
            throw new IllegalArgumentException("AI JWT 验证配置无效");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        decoder.setJwtValidator(new StrictSupportJwtValidator(issuer, audience, Clock.systemUTC()));
        return decoder;
    }

    @Bean
    SecurityFilterChain supportSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/ai/support/answers").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    jsonError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证"))
                .accessDeniedHandler((request, response, exception) ->
                    jsonError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问")))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { })
                .authenticationEntryPoint((request, response, exception) ->
                    jsonError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证")))
            .build();
    }

    private static void jsonError(HttpServletResponse response, HttpStatus status,
                                  String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
            + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}");
    }

    private static final class StrictSupportJwtValidator implements OAuth2TokenValidator<Jwt> {
        private final OAuth2TokenValidator<Jwt> standard;
        private final String issuer;
        private final String audience;
        private final Clock clock;

        private StrictSupportJwtValidator(String issuer, String audience, Clock clock) {
            this.standard = JwtValidators.createDefaultWithIssuer(issuer);
            this.issuer = issuer;
            this.audience = audience;
            this.clock = clock;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<OAuth2Error> errors = new ArrayList<>(standard.validate(jwt).getErrors());
            if (jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())) {
                errors.add(error("JWT issuer 非法"));
            }
            if (!List.of(audience).equals(jwt.getAudience())) {
                errors.add(error("JWT audience 非法"));
            }
            if (!"RS256".equals(jwt.getHeaders().get("alg"))) {
                errors.add(error("JWT algorithm 非法"));
            }
            Object keyId = jwt.getHeaders().get("kid");
            if (!(keyId instanceof String value) || value.isBlank()) {
                errors.add(error("JWT kid 非法"));
            }
            if (!canonicalUuid(jwt.getSubject())) {
                errors.add(error("JWT 用户 ID 非法"));
            }
            validateRoles(jwt.getClaims().get("roles"), errors);
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            Instant now = clock.instant();
            if (issuedAt == null || expiresAt == null || issuedAt.isAfter(now)
                || !expiresAt.isAfter(now)
                || !ACCESS_TOKEN_TTL.equals(Duration.between(issuedAt, expiresAt))) {
                errors.add(error("JWT 时间范围非法"));
            }
            return errors.isEmpty() ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(errors);
        }

        private static boolean canonicalUuid(String subject) {
            if (subject == null || subject.isBlank()) return false;
            try {
                return UUID.fromString(subject).toString().equalsIgnoreCase(subject);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        private static void validateRoles(Object rawRoles, List<OAuth2Error> errors) {
            if (!(rawRoles instanceof Collection<?> roles) || roles.isEmpty()
                || roles.stream().anyMatch(role -> !(role instanceof String text)
                    || !ALLOWED_ROLES.contains(text))) {
                errors.add(error("JWT 角色非法"));
            }
        }

        private static OAuth2Error error(String message) {
            return new OAuth2Error("invalid_token", message, null);
        }
    }
}
