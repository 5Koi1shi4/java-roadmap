package com.example.campusmarket.supportai.application;

import com.example.campusmarket.supportai.api.AnswerRequest;
import com.example.campusmarket.supportai.policy.PolicyChunk;
import com.example.campusmarket.supportai.policy.PolicyRetriever;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** AI 支持回答用例；明确保证授权、脱敏、检索和模型调用的先后顺序。 */
public final class AnswerService {
    private static final String STATUS_ONLY_ANSWER = "已显示当前状态；规则问题缺少足够依据。";
    private static final String NO_RULE_ANSWER = "没有足够的规则依据。";

    private final PolicyRetriever retriever;
    private final TradeStatusReader statusReader;
    private final PrivateQuestionClassifier classifier;
    private final AnswerModel model;

    public AnswerService(PolicyRetriever retriever, TradeStatusReader statusReader,
                         PrivateQuestionClassifier classifier, AnswerModel model) {
        this.retriever = Objects.requireNonNull(retriever, "规则检索器不能为空");
        this.statusReader = Objects.requireNonNull(statusReader, "交易状态客户端不能为空");
        this.classifier = Objects.requireNonNull(classifier, "私人问题分类器不能为空");
        this.model = Objects.requireNonNull(model, "回答模型不能为空");
    }

    /** 兼容按端口先行排列依赖的调用方。 */
    public AnswerService(TradeStatusReader statusReader, PrivateQuestionClassifier classifier,
                         PolicyRetriever retriever, AnswerModel model) {
        this(retriever, statusReader, classifier, model);
    }

    /**
     * 执行一次回答。私人资源必须先由交易服务按 Bearer 授权读取，之后只允许安全模板
     * 进入规则检索和模型；来源完全由服务端根据检索片段生成。
     */
    public AnswerResponse answer(AnswerRequest request, String bearer) {
        Objects.requireNonNull(request, "回答请求不能为空");
        StatusView status = null;
        String question = request.question();
        if (request.hasPrivateResource()) {
            if (bearer == null || bearer.isBlank()) {
                throw new UnauthorizedException();
            }
            TradeStatusReader.StatusView rawStatus =
                statusReader.read(request.type(), request.resourceId(), bearer);
            if (rawStatus == null) {
                throw new ResourceNotFoundException();
            }
            status = StatusView.from(rawStatus);
            question = classifier.template(request.question());
            if (question == null) {
                return new AnswerResponse(STATUS_ONLY_ANSWER, List.of(), status);
            }
        }

        List<PolicyChunk> chunks = retriever.find(question);
        if (chunks == null || chunks.isEmpty()) {
            return new AnswerResponse(NO_RULE_ANSWER, List.of(), status);
        }
        String explanation = model.explain(question, List.copyOf(chunks));
        if (explanation == null || explanation.isBlank()) {
            throw new DependencyUnavailableException("回答模型未返回内容");
        }
        List<SourceView> sources = chunks.stream()
            .map(chunk -> new SourceView(chunk.sourceId(), chunk.title(), chunk.version()))
            .toList();
        return new AnswerResponse(explanation, sources, status);
    }

    /** AI 服务自己的确定性状态副本。 */
    public record StatusView(UUID id, String type, String status,
                             java.time.Instant createdAt, java.time.Instant deadline) {
        public StatusView {
            Objects.requireNonNull(id, "资源 ID 不能为空");
            Objects.requireNonNull(type, "资源类型不能为空");
            Objects.requireNonNull(status, "资源状态不能为空");
            Objects.requireNonNull(createdAt, "创建时间不能为空");
        }

        public static StatusView from(TradeStatusReader.StatusView status) {
            Objects.requireNonNull(status, "状态不能为空");
            return new StatusView(status.id(), status.type(), status.status(),
                status.createdAt(), status.deadline());
        }
    }

    /** 服务端签发的规则来源摘要；客户端不能提交或覆盖这些字段。 */
    public record SourceView(String sourceId, String title, String version) {
        public SourceView {
            requireText(sourceId, "规则来源 ID");
            requireText(title, "规则标题");
            requireText(version, "规则版本");
        }

        private static void requireText(String value, String field) {
            Objects.requireNonNull(value, field + "不能为空");
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + "不能为空");
            }
        }
    }

    /** 面向客服前端的回答数据。 */
    public record AnswerResponse(String answer, List<SourceView> sources, StatusView status) {
        public AnswerResponse {
            Objects.requireNonNull(answer, "回答不能为空");
            if (answer.isBlank()) {
                throw new IllegalArgumentException("回答不能为空");
            }
            sources = List.copyOf(Objects.requireNonNull(sources, "规则来源不能为空"));
        }

        public AnswerResponse(String answer) {
            this(answer, List.of(), null);
        }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException() {
            super("未认证");
        }
    }

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException() {
            super("资源不存在");
        }

        public ResourceNotFoundException(String message) {
            super(message);
        }
    }

    public static class DependencyUnavailableException extends RuntimeException {
        public DependencyUnavailableException(String message) {
            super(message);
        }

        public DependencyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException() {
            super("请求过于频繁");
        }
    }
}
