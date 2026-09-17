package com.example.campusmarket.supportai.policy;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;

/** 应用就绪前把当前审定语料发布为唯一可读的版本化索引。 */
public final class PolicyIndexBootstrap implements ApplicationRunner {
    private final PolicyCorpus corpus;
    private final ElasticsearchPolicyIndex index;

    public PolicyIndexBootstrap(PolicyCorpus corpus, ElasticsearchPolicyIndex index) {
        this.corpus = Objects.requireNonNull(corpus, "规则语料不能为空");
        this.index = Objects.requireNonNull(index, "规则索引不能为空");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!index.rebuild(corpus)) {
            throw new IllegalStateException("规则索引初始化失败");
        }
    }
}
