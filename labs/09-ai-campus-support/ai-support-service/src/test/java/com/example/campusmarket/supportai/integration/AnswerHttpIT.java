package com.example.campusmarket.supportai.integration;

import com.example.campusmarket.supportai.api.AnswerRequest;
import com.example.campusmarket.supportai.api.SupportAnswerController;
import com.example.campusmarket.supportai.application.AnswerModel;
import com.example.campusmarket.supportai.application.AnswerService;
import com.example.campusmarket.supportai.application.PrivateQuestionClassifier;
import com.example.campusmarket.supportai.application.TradeStatusReader;
import com.example.campusmarket.supportai.infrastructure.SupportRateLimiter;
import com.example.campusmarket.supportai.policy.PolicyChunk;
import com.example.campusmarket.supportai.policy.PolicyRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 受控 HTTP 边界验证中文编码、授权和供应商/限流故障归类。 */
class AnswerHttpIT {
    private static final MediaType JSON_UTF8 = new MediaType(
        "application", "json", StandardCharsets.UTF_8);
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final PolicyRetriever retriever = mock(PolicyRetriever.class);
    private final TradeStatusReader statusReader = mock(TradeStatusReader.class);
    private final PrivateQuestionClassifier classifier = new PrivateQuestionClassifier();
    private final AnswerModel model = mock(AnswerModel.class);
    private final SupportRateLimiter limiter = mock(SupportRateLimiter.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AnswerService service = new AnswerService(retriever, statusReader, classifier, model);
        mvc = MockMvcBuilders.standaloneSetup(
            new SupportAnswerController(service, limiter)).build();
    }

    @Test
    void chineseAnswerHasExplicitUtf8AndNoPrivateDataAtModelBoundary() throws Exception {
        PolicyChunk rule = new PolicyChunk("trade-policy", "退款规则", "v1",
            "退款规则\n\n符合条件后原路退回", 0.93d);
        when(retriever.find("退款规则")).thenReturn(List.of(rule));
        when(model.explain("退款规则", List.of(rule))).thenReturn("当前状态：PAID；退款规则说明");
        when(statusReader.read("orders", ORDER_ID, "buyer-token"))
            .thenReturn(new TradeStatusReader.StatusView(ORDER_ID, "orders", "PAID",
                Instant.parse("2026-09-17T00:00:00Z"), null));

        var result = mvc.perform(post("/api/ai/support/answers")
                .with(request -> {
                    request.setUserPrincipal(authenticated("buyer-token"));
                    return request;
                })
                .header("Authorization", "Bearer buyer-token")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"我的订单 " + ORDER_ID + " 为什么还没退款\","
                    + "\"orderId\":\"" + ORDER_ID + "\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(JSON_UTF8))
            .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("当前状态", "退款规则", ORDER_ID.toString());
        verify(model).explain(eq("退款规则"), eq(List.of(rule)));
    }

    @Test
    void unauthorizedPrivateRequestStopsBeforeService() throws Exception {
        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"订单状态\",\"orderId\":\""
                    + ORDER_ID + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(JSON_UTF8));
    }

    @Test
    void unauthorizedResourceIsNotFound() throws Exception {
        when(statusReader.read("orders", ORDER_ID, "stranger-token"))
            .thenThrow(new AnswerService.ResourceNotFoundException());

        mvc.perform(post("/api/ai/support/answers")
                .with(request -> {
                    request.setUserPrincipal(authenticated("stranger-token"));
                    return request;
                })
                .header("Authorization", "Bearer stranger-token")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"退款规则\",\"orderId\":\""
                    + ORDER_ID + "\"}"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(JSON_UTF8));
    }

    @Test
    void modelFailureIsServiceUnavailable() throws Exception {
        when(retriever.find("退款规则")).thenThrow(
            new AnswerService.DependencyUnavailableException("provider down"));

        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"退款规则\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentType(JSON_UTF8));
    }

    @Test
    void rateLimitFailureIsTooManyRequests() throws Exception {
        doThrow(new AnswerService.RateLimitExceededException())
            .when(limiter).check(any(), any());

        mvc.perform(post("/api/ai/support/answers")
                .contentType(JSON_UTF8)
                .content("{\"question\":\"退款规则\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().contentType(JSON_UTF8));
    }

    private static TestingAuthenticationToken authenticated(String token) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
            USER_ID.toString(), token);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
