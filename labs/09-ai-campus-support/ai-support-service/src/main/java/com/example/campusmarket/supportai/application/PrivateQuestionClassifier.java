package com.example.campusmarket.supportai.application;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将绑定私人资源的问题归一化为不含资源标识的公开规则模板。 */
@Component
public final class PrivateQuestionClassifier {
    private static final Map<String, String> RULE_TEMPLATES = templates();

    /**
     * 返回白名单规则模板；无法证明是规则问题时返回 null。
     *
     * <p>返回值只来自固定常量，不包含输入问题的任何字符，因而不会把订单号、案件号
     * 或私人描述带入后续检索和模型请求。</p>
     */
    public String template(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        return RULE_TEMPLATES.entrySet().stream()
            .filter(entry -> question.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private static Map<String, String> templates() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("退款规则", "退款规则");
        result.put("退款", "退款规则");
        result.put("退货", "退款规则");
        result.put("试用期限", "试用期限");
        result.put("试用", "试用期限");
        result.put("质保期限", "质保期限");
        result.put("质保", "质保期限");
        result.put("保修", "质保期限");
        return Map.copyOf(result);
    }
}
