package com.example.campusmarket.supportai.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** AI 支持问答请求；公开问题不绑定交易资源，私人问题只能绑定一个资源。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AnswerRequest(String question, UUID orderId, UUID caseId, CaseType caseType) {
    private static final int MAX_QUESTION_BYTES = 2 * 1024;

    public AnswerRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (question.getBytes(StandardCharsets.UTF_8).length > MAX_QUESTION_BYTES) {
            throw new IllegalArgumentException("问题不能超过 2 KiB");
        }
        if (orderId != null && caseId != null) {
            throw new IllegalArgumentException("订单和案件资源不能同时存在");
        }
        if (orderId != null && caseType != null) {
            throw new IllegalArgumentException("订单问题不能带案件类型");
        }
        if (caseId == null && caseType != null) {
            throw new IllegalArgumentException("案件类型必须绑定案件");
        }
        if (caseId != null && caseType == null) {
            throw new IllegalArgumentException("案件问题必须指定案件类型");
        }
    }

    public boolean hasPrivateResource() {
        return orderId != null || caseId != null;
    }

    public UUID resourceId() {
        return orderId != null ? orderId : caseId;
    }

    public String type() {
        if (orderId != null) {
            return "orders";
        }
        if (caseId == null) {
            return null;
        }
        return caseType == CaseType.DISPUTE ? "disputes" : "warranties";
    }

    public enum CaseType {
        DISPUTE,
        WARRANTY
    }
}
