package com.example.campusmarket.supportai.policy;

import java.util.Objects;

/** 可供回答模型引用的、带来源版本的公开规则片段。 */
public record PolicyChunk(String sourceId, String title, String version, String text,
                          double score) {
    public PolicyChunk {
        sourceId = requireText(sourceId, "规则来源 ID");
        title = requireText(title, "规则标题");
        version = requireText(version, "规则版本");
        text = requireText(text, "规则正文");
        if (!Double.isFinite(score) || score < 0.0d) {
            throw new IllegalArgumentException("规则相似度分数无效");
        }
    }

    /** 为索引结果附加相似度分数。 */
    public PolicyChunk withScore(double score) {
        return new PolicyChunk(sourceId, title, version, text, score);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }
}
