package com.example.campusmarket.integration;

import com.example.campusmarket.testsupport.TestJwtFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 所有资源服务器集成测试共用固定测试 JWKS 和平台 JWT 夹具。 */
public final class ResourceServerTestSupport {
    public static final String ISSUER = "http://gateway.test";
    public static final String AUDIENCE = "campus-market-api";
    public static final String KID = "test-key-1";

    private static final HttpServer JWKS_SERVER = startJwksServer();

    private ResourceServerTestSupport() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("campus.market.jwt.audience", () -> AUDIENCE);
    }

    public static String token(UUID userId, Set<String> roles) {
        return TestJwtFactory.issue(userId, roles, Instant.now(), TestJwtFactory.ACCESS_TTL,
            ISSUER, AUDIENCE, KID);
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RSAKey publicJwk = TestJwtFactory.publicJwk(KID);
            String body = new ObjectMapper().writeValueAsString(
                Map.of("keys", List.of(publicJwk.toJSONObject())));
            server.createContext("/jwks", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException("无法启动 JWKS 测试桩", ex);
        }
    }
}
