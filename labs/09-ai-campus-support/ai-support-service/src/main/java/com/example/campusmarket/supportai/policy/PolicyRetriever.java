package com.example.campusmarket.supportai.policy;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** AI 应用层使用的公开规则检索边界。 */
public final class PolicyRetriever {
    public static final double DEFAULT_MIN_SCORE = 0.70d;
    public static final int DEFAULT_LIMIT = 5;

    private final PolicyCorpus corpus;
    private final ElasticsearchPolicyIndex index;
    private final double minimumScore;
    private final int limit;

    public PolicyRetriever(PolicyCorpus corpus, ElasticsearchPolicyIndex index) {
        this(corpus, index, DEFAULT_MIN_SCORE, DEFAULT_LIMIT);
    }

    public PolicyRetriever(ElasticsearchPolicyIndex index, PolicyCorpus corpus) {
        this(corpus, index, DEFAULT_MIN_SCORE, DEFAULT_LIMIT);
    }

    public PolicyRetriever(PolicyCorpus corpus, ElasticsearchPolicyIndex index,
                           double minimumScore, int limit) {
        this.corpus = Objects.requireNonNull(corpus, "规则语料不能为空");
        this.index = Objects.requireNonNull(index, "规则索引不能为空");
        if (!Double.isFinite(minimumScore) || minimumScore < 0.0d) {
            throw new IllegalArgumentException("规则相似度门槛无效");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("规则检索数量必须为正数");
        }
        this.minimumScore = minimumScore;
        this.limit = limit;
    }

    /** 只返回当前语料版本、已通过相似度门槛且来源仍在公开清单中的片段。 */
    public List<PolicyChunk> find(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (!corpus.version().equals(index.aliasVersion())) {
            throw new PolicyUnavailableException("规则索引版本不可用");
        }
        Set<String> sourceIds = corpus.sources().stream()
            .filter(PolicyCorpus.Source::publicSource)
            .map(PolicyCorpus.Source::sourceId)
            .collect(Collectors.toUnmodifiableSet());
        return index.similaritySearch(question, limit).stream()
            .filter(chunk -> sourceIds.contains(chunk.sourceId()))
            .filter(chunk -> corpus.version().equals(chunk.version()))
            .filter(chunk -> chunk.score() >= minimumScore)
            .toList();
    }

    public PolicyCorpus corpus() {
        return corpus;
    }

    public double minimumScore() {
        return minimumScore;
    }

    public static final class PolicyUnavailableException extends RuntimeException {
        public PolicyUnavailableException(String message) {
            super(message);
        }

        public PolicyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
