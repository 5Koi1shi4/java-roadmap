package com.example.campusmarket.security;

import com.example.campusmarket.api.SecurityConfiguration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 独立 OAuth2 Resource Server：只通过身份服务 JWKS 验证 RS256 Bearer Token。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResourceServerConfiguration {
    static final String REQUIRED_AUDIENCE = "campus-market-api";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    @Bean
    JwtPrincipalConverter jwtPrincipalConverter() {
        return new JwtPrincipalConverter();
    }

    @Bean(name = "jwksRestOperations")
    RestOperations jwksRestOperations() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(3_000);
        return new RestTemplate(requestFactory);
    }

    @Bean(name = "jwks")
    HealthIndicator jwksReadinessIndicator(
        @Qualifier("jwksRestOperations") RestOperations restOperations,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        return new JwksReadinessHealthIndicator(restOperations, jwkSetUri);
    }

    @Bean(name = "eureka")
    HealthIndicator eurekaReadinessIndicator(ObjectProvider<DiscoveryClient> discoveryClientProvider,
                                             @Qualifier("jwksRestOperations") RestOperations client,
                                             @Value("${eureka.client.service-url.defaultZone:http://localhost:8761/eureka/}")
                                             String eurekaZone) {
        String endpoint = eurekaZone.endsWith("/") ? eurekaZone + "apps" : eurekaZone + "/apps";
        return new EurekaReadinessHealthIndicator(discoveryClientProvider,
            () -> {
                client.getForObject(endpoint, String.class);
                return true;
            });
    }

    @Bean
    JwtDecoder jwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${campus.market.jwt.audience:campus-market-api}") String audience) {
        if (!REQUIRED_AUDIENCE.equals(audience)) {
            throw new IllegalArgumentException("JWT audience must be " + REQUIRED_AUDIENCE);
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .restOperations(jwksRestOperations())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        decoder.setJwtValidator(new StrictJwtValidator(issuer, audience, Clock.systemUTC()));
        return new ResourceServerJwtDecoder(decoder);
    }

    @Bean
    SecurityFilterChain resourceServerSecurityFilterChain(
        HttpSecurity http, JwtPrincipalConverter principalConverter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/payment-webhooks/**", "/actuator/health",
                    "/actuator/health/**",
                    "/simulated-provider/**", "/api/simulated-provider/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    writeJsonError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证"))
                .accessDeniedHandler((request, response, exception) ->
                    writeJsonError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问")))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    try {
                        return principalConverter.convert(token);
                    } catch (JwtException invalidClaims) {
                        throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                            new OAuth2Error("invalid_token", invalidClaims.getMessage(), null),
                            invalidClaims.getMessage(), invalidClaims);
                    }
                }))
                .authenticationEntryPoint((request, response, exception) ->
                    writeJsonError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证")))
            .build();
    }

    private static void writeJsonError(HttpServletResponse response, HttpStatus status,
                                       String code, String message) throws IOException {
        SecurityConfiguration.writeJsonError(response, status, code, message);
    }

    private static final class StrictJwtValidator implements OAuth2TokenValidator<Jwt> {
        private final OAuth2TokenValidator<Jwt> standard;
        private final String audience;
        private final Clock clock;

        private StrictJwtValidator(String issuer, String audience, Clock clock) {
            this.standard = JwtValidators.createDefaultWithIssuer(issuer);
            this.audience = audience;
            this.clock = clock;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<OAuth2Error> errors = new ArrayList<>(standard.validate(jwt).getErrors());
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null) {
                errors.add(new OAuth2Error("invalid_token", "JWT 必须包含 iat 和 exp", null));
            } else {
                Instant now = clock.instant();
                if (issuedAt.isAfter(now) || !expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt)
                    || !ACCESS_TOKEN_TTL.equals(Duration.between(issuedAt, expiresAt))) {
                    errors.add(new OAuth2Error("invalid_token", "JWT 时间范围非法", null));
                }
            }
            Object keyId = jwt.getHeaders().get("kid");
            if (!(keyId instanceof String value) || value.isBlank()) {
                errors.add(new OAuth2Error("invalid_token", "JWT kid 非法", null));
            }
            if (!List.of(audience).equals(jwt.getAudience())) {
                errors.add(new OAuth2Error("invalid_token", "JWT 受众非法", null));
            }
            return errors.isEmpty() ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(errors);
        }
    }
}
