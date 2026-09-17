package com.example.campusmarket.supportai;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.campusmarket.supportai.application.AnswerModel;
import com.example.campusmarket.supportai.application.AnswerService;
import com.example.campusmarket.supportai.application.PrivateQuestionClassifier;
import com.example.campusmarket.supportai.application.TradeStatusReader;
import com.example.campusmarket.supportai.policy.ElasticsearchPolicyIndex;
import com.example.campusmarket.supportai.policy.PolicyIndexBootstrap;
import com.example.campusmarket.supportai.policy.PolicyRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 生产 AI 配置必须把规则索引、检索与回答用例作为完整链路装配。 */
class SupportAiConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SupportAiConfiguration.class, Dependencies.class)
        .withPropertyValues("campus.market.ai.policy-directory=policies");

    @Test
    void assemblesCompleteAnswerChainWhenDependenciesExist() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ElasticsearchPolicyIndex.class);
            assertThat(context).hasSingleBean(PolicyRetriever.class);
            assertThat(context).hasSingleBean(PolicyIndexBootstrap.class);
            assertThat(context).hasSingleBean(AnswerService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        ElasticsearchClient elasticsearchClient() {
            return mock(ElasticsearchClient.class);
        }

        @Bean
        TradeStatusReader tradeStatusReader() {
            return mock(TradeStatusReader.class);
        }

        @Bean
        PrivateQuestionClassifier privateQuestionClassifier() {
            return new PrivateQuestionClassifier();
        }

        @Bean
        AnswerModel answerModel() {
            return mock(AnswerModel.class);
        }
    }
}
