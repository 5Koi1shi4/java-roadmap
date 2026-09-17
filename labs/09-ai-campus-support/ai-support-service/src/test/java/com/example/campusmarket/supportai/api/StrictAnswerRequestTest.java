package com.example.campusmarket.supportai.api;

import com.example.campusmarket.supportai.AiSupportApplication;
import com.example.campusmarket.testsupport.TestJwtFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** 验证 AI 问答请求的严格 JSON 边界。 */
@SpringBootTest(
    classes = AiSupportApplication.class,
    properties = {
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false"
    })
@ActiveProfiles("test")
class StrictAnswerRequestTest {
    private static final MediaType JSON_UTF8 = new MediaType(
        "application", "json", StandardCharsets.UTF_8);
    private static final HttpServer JWKS_SERVER = startJwksServer();

    private MockMvc mvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private WebApplicationContext applicationContext;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "http://localhost:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
            () -> "http://gateway.test");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext)
            .apply(springSecurity())
            .build();
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void rejectsConflictingResourcesAndUnknownField() throws Exception {
        String orderId = "11111111-1111-1111-1111-111111111111";
        String caseId = "22222222-2222-2222-2222-222222222222";

        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"我的订单\",\"orderId\":\"" + orderId
                    + "\",\"caseId\":\"" + caseId + "\",\"caseType\":\"WARRANTY\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"退款规则\",\"extra\":1}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsQuestionBeyondTwoKiBOfUtf8() throws Exception {
        String question = "中".repeat(683);

        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"" + question + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPrivateQuestionWithoutIdentity() throws Exception {
        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"订单状态\","
                    + "\"orderId\":\"11111111-1111-1111-1111-111111111111\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void independentlyVerifiesBearerForPrivateQuestion() throws Exception {
        String body = "{\"question\":\"订单状态\","
            + "\"orderId\":\"11111111-1111-1111-1111-111111111111\"}";

        mvc.perform(post("/api/ai/support/answers")
                .header(AUTHORIZATION, "Bearer " + validToken())
                .contentType(JSON_UTF8)
                .content(body))
            .andExpect(status().isOk());

        mvc.perform(post("/api/ai/support/answers")
                .header(AUTHORIZATION, "Bearer invalid-token")
                .contentType(JSON_UTF8)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void startsWithStrictJwtDecoder() {
        assertThat(jwtDecoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void publicQuestionHasNoPrivateResourceMetadata() {
        AnswerRequest request = new AnswerRequest("退款规则", null, null, null);

        assertThat(request.hasPrivateResource()).isFalse();
        assertThat(request.resourceId()).isNull();
        assertThat(request.type()).isNull();
    }

    private static String validToken() {
        return TestJwtFactory.issue(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Set.of("ROLE_USER"), Instant.now().minusSeconds(1), Duration.ofMinutes(15),
            "http://gateway.test", "campus-market-api", "test-key-1");
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = ("{\"keys\":["
                    + TestJwtFactory.publicJwk("test-key-1").toJSONString() + "]}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动测试 JWKS 服务", exception);
        }
    }
}
