package com.example.campusmarket.supportai;

import com.example.campusmarket.supportai.application.AnswerModel;
import com.example.campusmarket.supportai.application.AnswerService;
import com.example.campusmarket.supportai.application.PrivateQuestionClassifier;
import com.example.campusmarket.supportai.application.TradeStatusReader;
import com.example.campusmarket.supportai.policy.ElasticsearchPolicyIndex;
import com.example.campusmarket.supportai.policy.PolicyCorpus;
import com.example.campusmarket.supportai.policy.PolicyIndexBootstrap;
import com.example.campusmarket.supportai.policy.PolicyRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import co.elastic.clients.elasticsearch.ElasticsearchClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** AI 校园支持服务启动入口。 */
@SpringBootApplication
public class AiSupportApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AiSupportApplication.class);
        application.setDefaultProperties(Map.of("spring.application.name", "ai-support-service"));
        application.run(args);
    }
}

/** 仅在显式启用 AI 运行时组装外部索引、交易客户端和回答用例。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "campus.market.ai.enabled", havingValue = "true",
    matchIfMissing = true)
class SupportAiConfiguration {
    @Bean
    PolicyCorpus policyCorpus(
        @Value("${campus.market.ai.policy-directory:policies}") String configuredDirectory) {
        Path configured = Path.of(configuredDirectory).toAbsolutePath().normalize();
        Path directory = Files.exists(configured) ? configured
            : Path.of("..", configuredDirectory).toAbsolutePath().normalize();
        return PolicyCorpus.load(directory);
    }

    @Bean
    ElasticsearchPolicyIndex elasticsearchPolicyIndex(ElasticsearchClient client) {
        return new ElasticsearchPolicyIndex(client);
    }

    @Bean
    PolicyRetriever policyRetriever(PolicyCorpus corpus, ElasticsearchPolicyIndex index) {
        return new PolicyRetriever(corpus, index);
    }

    @Bean
    PolicyIndexBootstrap policyIndexBootstrap(PolicyCorpus corpus, ElasticsearchPolicyIndex index) {
        return new PolicyIndexBootstrap(corpus, index);
    }

    @Bean
    AnswerService answerService(PolicyRetriever retriever, TradeStatusReader statusReader,
                                PrivateQuestionClassifier classifier, AnswerModel model) {
        return new AnswerService(retriever, statusReader, classifier, model);
    }
}
