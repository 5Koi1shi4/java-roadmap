package com.example.campusmarket.gateway.security;

import com.example.campusmarket.gateway.error.GatewayErrorWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Gateway 无状态 Resource Server 安全链和固定 JWT 契约。 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {
    private static final String REQUIRED_AUDIENCE = "campus-market-api";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    @Bean(name = "jwksWebClient")
    WebClient jwksWebClient() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)
            .responseTimeout(Duration.ofSeconds(3));
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    @Bean(name = "jwks")
    JwksReadinessHealthIndicator jwksReadinessIndicator(
        @Qualifier("jwksWebClient") WebClient jwksWebClient,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        return new JwksReadinessHealthIndicator(jwksWebClient, jwkSetUri);
    }

    @Bean(name = "eureka")
    EurekaReadinessHealthIndicator eurekaReadinessIndicator(
        ObjectProvider<DiscoveryClient> discoveryClientProvider,
        @Qualifier("jwksWebClient") WebClient client,
        @Value("${eureka.client.service-url.defaultZone:http://localhost:8761/eureka/}")
        String eurekaZone) {
        String endpoint = eurekaZone.endsWith("/") ? eurekaZone + "apps" : eurekaZone + "/apps";
        return new EurekaReadinessHealthIndicator(discoveryClientProvider,
            () -> {
                client.get().uri(endpoint).retrieve().toBodilessEntity().block(Duration.ofSeconds(4));
                return true;
            });
    }

    @Bean
    SecurityWebFilterChain gatewaySecurityFilterChain(ServerHttpSecurity http,
                                                       ReactiveJwtDecoder jwtDecoder,
                                                       GatewayErrorWriter errorWriter) {
        ServerAuthenticationEntryPoint authenticationEntryPoint = (exchange, ignored) ->
            errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证");
        ServerAuthenticationEntryPointFailureHandler authenticationFailureHandler =
            new ServerAuthenticationEntryPointFailureHandler(authenticationEntryPoint);
        authenticationFailureHandler.setRethrowAuthenticationServiceException(false);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .securityContextRepository(org.springframework.security.web.server.context
                .NoOpServerSecurityContextRepository.getInstance())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((exchange, ignored) ->
                    errorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED", "未认证"))
                .accessDeniedHandler((exchange, ignored) ->
                    errorWriter.write(exchange, HttpStatus.FORBIDDEN,
                        "FORBIDDEN", "无权访问")))
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .pathMatchers(HttpMethod.POST,
                    "/api/auth/email-verifications",
                    "/api/auth/register",
                    "/api/auth/login").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/auth/.well-known/jwks.json").permitAll()
                .pathMatchers("/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness", "/health").permitAll()
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .pathMatchers("/api/**").authenticated()
                .anyExchange().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(authenticationEntryPoint)
                .authenticationFailureHandler(authenticationFailureHandler))
            .build();
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${campus.market.jwt.audience:campus-market-api}") String audience,
        @Qualifier("jwksWebClient") WebClient jwksWebClient,
        MeterRegistry meterRegistry) {
        if (!REQUIRED_AUDIENCE.equals(audience)) {
            throw new IllegalArgumentException("JWT audience must be " + REQUIRED_AUDIENCE);
        }
        if (jwkSetUri == null || jwkSetUri.isBlank() || issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT resource server properties are required");
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
            .webClient(jwksWebClient)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(new StrictJwtValidator(standard, issuer, audience, Clock.systemUTC()));
        return new JwtFailureMetrics(decoder, meterRegistry);
    }

    private static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object rawRoles = jwt.getClaims().get("roles");
            if (!(rawRoles instanceof Collection<?> rawValues) || rawValues.isEmpty()) {
                throw new JwtException("JWT 角色非法");
            }
            List<GrantedAuthority> authorities = new ArrayList<>(rawValues.size());
            for (Object rawValue : rawValues) {
                if (!(rawValue instanceof String role) || !ALLOWED_ROLES.contains(role)) {
                    throw new JwtException("JWT 角色非法");
                }
                authorities.add(new SimpleGrantedAuthority(role));
            }
            return authorities;
        });
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }

    private static final class StrictJwtValidator implements OAuth2TokenValidator<Jwt> {
        private final OAuth2TokenValidator<Jwt> standard;
        private final String issuer;
        private final String audience;
        private final Clock clock;

        private StrictJwtValidator(OAuth2TokenValidator<Jwt> standard, String issuer,
                                   String audience, Clock clock) {
            this.standard = Objects.requireNonNull(standard, "标准 JWT 校验器不能为空");
            this.issuer = Objects.requireNonNull(issuer, "issuer 不能为空");
            this.audience = Objects.requireNonNull(audience, "audience 不能为空");
            this.clock = Objects.requireNonNull(clock, "JWT clock 不能为空");
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<OAuth2Error> errors = new ArrayList<>(standard.validate(jwt).getErrors());
            if (jwt.getIssuer() == null || !Objects.equals(issuer, jwt.getIssuer().toString())) {
                errors.add(error("JWT issuer 非法"));
            }
            if (!List.of(audience).equals(jwt.getAudience())) {
                errors.add(error("JWT audience 非法"));
            }
            if (!Objects.equals("RS256", jwt.getHeaders().get("alg"))) {
                errors.add(error("JWT algorithm 非法"));
            }
            Object rawKid = jwt.getHeaders().get("kid");
            if (!(rawKid instanceof String actualKid) || actualKid.isBlank()) {
                errors.add(error("JWT kid 非法"));
            }
            validateSubject(jwt.getSubject(), errors);
            validateRoles(jwt.getClaims().get("roles"), errors);
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null) {
                errors.add(error("JWT 必须包含 iat 和 exp"));
            } else {
                Instant now = clock.instant();
                if (issuedAt.isAfter(now) || !expiresAt.isAfter(now)
                    || !ACCESS_TOKEN_TTL.equals(Duration.between(issuedAt, expiresAt))) {
                    errors.add(error("JWT 时间范围非法"));
                }
            }
            return errors.isEmpty() ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(errors);
        }

        private static void validateSubject(String subject, List<OAuth2Error> errors) {
            if (subject == null || subject.isBlank()) {
                errors.add(error("JWT subject 非法"));
                return;
            }
            try {
                UUID parsed = UUID.fromString(subject);
                if (!parsed.toString().equalsIgnoreCase(subject)) {
                    errors.add(error("JWT subject 非法"));
                }
            } catch (IllegalArgumentException ex) {
                errors.add(error("JWT subject 非法"));
            }
        }

        private static void validateRoles(Object rawRoles, List<OAuth2Error> errors) {
            if (!(rawRoles instanceof Collection<?> values) || values.isEmpty()
                || values.stream().anyMatch(value -> !(value instanceof String role)
                    || role.isBlank() || !ALLOWED_ROLES.contains(role))) {
                errors.add(error("JWT roles 非法"));
            }
        }

        private static OAuth2Error error(String description) {
            return new OAuth2Error("invalid_token", description, null);
        }
    }
}
