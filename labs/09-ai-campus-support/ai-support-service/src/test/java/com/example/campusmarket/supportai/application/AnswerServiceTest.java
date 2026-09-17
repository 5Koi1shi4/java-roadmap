package com.example.campusmarket.supportai.application;

import com.example.campusmarket.supportai.api.AnswerRequest;
import com.example.campusmarket.supportai.policy.PolicyChunk;
import com.example.campusmarket.supportai.policy.PolicyRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证授权优先、私人问题脱敏和受控回答编排。 */
class AnswerServiceTest {
    private final PolicyRetriever retriever = mock(PolicyRetriever.class);
    private final TradeStatusReader statusReader = mock(TradeStatusReader.class);
    private final PrivateQuestionClassifier classifier = mock(PrivateQuestionClassifier.class);
    private final AnswerModel model = mock(AnswerModel.class);
    private final AnswerService service = new AnswerService(retriever, statusReader, classifier, model);
    private final UUID orderId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void privateRequestNeverSendsRawQuestionOrUuidToModel() {
        String privateQuestion = "我的订单 " + orderId + " 为什么还没退款";
        PolicyChunk refundRule = new PolicyChunk("trade-policy", "退款规则", "v1",
            "退款规则\n\n符合条件后原路退回", 0.91d);
        when(statusReader.read("orders", orderId, "buyer-token"))
            .thenReturn(new TradeStatusReader.StatusView(orderId, "orders", "PAID",
                Instant.parse("2026-09-17T00:00:00Z"), null));
        when(classifier.template(privateQuestion)).thenReturn("退款规则");
        when(retriever.find("退款规则")).thenReturn(List.of(refundRule));
        when(model.explain("退款规则", List.of(refundRule))).thenReturn("退款规则说明");

        AnswerService.AnswerResponse response = service.answer(
            new AnswerRequest(privateQuestion, orderId, null, null), "buyer-token");

        assertThat(response.status().status()).isEqualTo("PAID");
        assertThat(response.answer()).isEqualTo("退款规则说明");
        verify(model).explain(eq("退款规则"), any());
        verify(model, never()).explain(eq(privateQuestion), any());
    }

    @Test
    void unauthorizedResourceStopsBeforeRetrievalAndModel() {
        when(statusReader.read("orders", orderId, "stranger-token"))
            .thenThrow(new AnswerService.ResourceNotFoundException());

        assertThatThrownBy(() -> service.answer(
            new AnswerRequest("订单状态", orderId, null, null), "stranger-token"))
            .isInstanceOf(AnswerService.ResourceNotFoundException.class);
        verifyNoDownstreamCalls();
    }

    @Test
    void unsupportedPrivateQuestionReturnsStatusWithoutRetrievalOrModel() {
        String question = "我的订单为什么显示这个状态";
        when(statusReader.read("orders", orderId, "buyer-token"))
            .thenReturn(new TradeStatusReader.StatusView(orderId, "orders", "PAID",
                Instant.parse("2026-09-17T00:00:00Z"), null));
        when(classifier.template(question)).thenReturn(null);

        AnswerService.AnswerResponse response = service.answer(
            new AnswerRequest(question, orderId, null, null), "buyer-token");

        assertThat(response.answer()).contains("当前状态");
        assertThat(response.sources()).isEmpty();
        verifyNoDownstreamCalls();
    }

    @Test
    void publicQuestionRetrievesRulesAndAddsOnlyServerSources() {
        PolicyChunk rule = new PolicyChunk("trade-policy", "试用期限", "v1",
            "试用期限规则", 0.88d);
        when(retriever.find("试用期限")).thenReturn(List.of(rule));
        when(model.explain("试用期限", List.of(rule))).thenReturn("公开规则说明");

        AnswerService.AnswerResponse response = service.answer(
            new AnswerRequest("试用期限", null, null, null), null);

        assertThat(response.status()).isNull();
        assertThat(response.sources()).containsExactly(
            new AnswerService.SourceView("trade-policy", "试用期限", "v1"));
        verify(statusReader, never()).read(any(), any(), any());
    }

    @Test
    void privateQuestionClassifierOnlyReturnsWhitelistedRuleTemplate() {
        PrivateQuestionClassifier realClassifier = new PrivateQuestionClassifier();

        assertThat(realClassifier.template("我的订单 " + orderId + " 为什么还没退款"))
            .isEqualTo("退款规则");
        assertThat(realClassifier.template("我的订单 " + orderId + " 什么时候发货"))
            .isNull();
    }

    private void verifyNoDownstreamCalls() {
        verify(retriever, never()).find(any());
        verify(model, never()).explain(any(), any());
    }
}
