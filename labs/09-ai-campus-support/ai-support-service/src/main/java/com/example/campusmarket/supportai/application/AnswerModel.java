package com.example.campusmarket.supportai.application;

import com.example.campusmarket.supportai.policy.PolicyChunk;

import java.util.List;

/** 受控回答模型端口；模型只能接收安全模板和公开规则片段。 */
@FunctionalInterface
public interface AnswerModel {
    String explain(String template, List<PolicyChunk> chunks);
}
