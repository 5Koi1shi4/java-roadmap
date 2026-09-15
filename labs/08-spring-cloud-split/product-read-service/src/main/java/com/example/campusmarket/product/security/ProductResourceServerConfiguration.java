package com.example.campusmarket.product.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestOperations;
import co.elastic.clients.elasticsearch.ElasticsearchClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 商品读服务独立验证身份服务 RS256 JWKS，不采信 Gateway 注入的身份 Header。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProductResourceServerConfiguration {
    private static final String REQUIRED_AUDIENCE = "campus-market-api";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    @Bean(name = "productReadinessRestOperations")
    RestOperations productReadinessRestOperations() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(1_000);
        requests.setReadTimeout(3_000);
        return new RestTemplate(requests);
    }

    @Bean(name = "jwks")
    HealthIndicator productJwksReadiness(
        @Qualifier("productReadinessRestOperations") RestOperations client,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwksUri) {
        return new JwksReadinessHealthIndicator(client, jwksUri);
    }

    @Bean(name = "eureka")
    HealthIndicator productEurekaReadiness(ObjectProvider<DiscoveryClient> discovery,
        @Qualifier("productReadinessRestOperations") RestOperations client,
        @Value("${eureka.client.service-url.defaultZone:http://localhost:8761/eureka/}") String zone) {
        String endpoint = zone.endsWith("/") ? zone + "apps" : zone + "/apps";
        return new EurekaReadinessHealthIndicator(discovery, () -> {
            client.getForObject(endpoint, String.class);
            return true;
        });
    }

    @Bean(name = "productSearch")
    HealthIndicator productSearchReadiness(ObjectProvider<ElasticsearchClient> searchClient) {
        return () -> {
            try {
                ElasticsearchClient client = searchClient.getIfAvailable();
                return client != null && client.ping().value()
                    ? Health.up().build() : Health.down().build();
            } catch (Exception unavailable) {
                return Health.down().build();
            }
        };
    }

    @Bean
    JwtDecoder productJwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${campus.market.jwt.audience:campus-market-api}") String audience) {
        if (jwkSetUri == null || jwkSetUri.isBlank() || issuer == null || issuer.isBlank()
            || !REQUIRED_AUDIENCE.equals(audience)) {
            throw new IllegalArgumentException("商品 JWT 验证配置无效");
        }
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(1_000);
        requests.setReadTimeout(3_000);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .restOperations(new RestTemplate(requests))
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        decoder.setJwtValidator(new StrictProductJwtValidator(issuer, audience, Clock.systemUTC()));
        return token -> {
            try {
                return decoder.decode(token);
            } catch (BadJwtException invalid) {
                throw invalid;
            } catch (JwtException unavailableOrInvalid) {
                throw new BadJwtException("商品 JWT 验证失败", unavailableOrInvalid);
            }
        };
    }

    @Bean
    SecurityFilterChain productSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/search", "/api/listings/search").authenticated()
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

    private static final class StrictProductJwtValidator implements OAuth2TokenValidator<Jwt> {
        private final OAuth2TokenValidator<Jwt> standard;
        private final String audience;
        private final Clock clock;

        private StrictProductJwtValidator(String issuer, String audience, Clock clock) {
            this.standard = JwtValidators.createDefaultWithIssuer(issuer);
            this.audience = audience;
            this.clock = clock;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<OAuth2Error> errors = new ArrayList<>(standard.validate(jwt).getErrors());
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            Instant now = clock.instant();
            if (issuedAt == null || expiresAt == null || issuedAt.isAfter(now)
                || !expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt)
                || !ACCESS_TTL.equals(Duration.between(issuedAt, expiresAt))) {
                errors.add(error("JWT 时间范围非法"));
            }
            Object keyId = jwt.getHeaders().get("kid");
            if (!(keyId instanceof String value) || value.isBlank()) {
                errors.add(error("JWT kid 非法"));
            }
            if (!List.of(audience).equals(jwt.getAudience())) {
                errors.add(error("JWT 受众非法"));
            }
            if (!canonicalUuid(jwt.getSubject())) {
                errors.add(error("JWT 用户 ID 非法"));
            }
            Object rawRoles = jwt.getClaims().get("roles");
            if (!(rawRoles instanceof List<?> roles) || roles.isEmpty()
                || roles.stream().anyMatch(role -> !(role instanceof String text)
                    || !ALLOWED_ROLES.contains(text))) {
                errors.add(error("JWT 角色非法"));
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

        private static OAuth2Error error(String message) {
            return new OAuth2Error("invalid_token", message, null);
        }
    }
}
