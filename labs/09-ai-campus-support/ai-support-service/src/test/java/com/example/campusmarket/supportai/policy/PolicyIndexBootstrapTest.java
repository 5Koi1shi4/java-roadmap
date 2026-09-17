package com.example.campusmarket.supportai.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyIndexBootstrapTest {
    @Test
    void startupPublishesReviewedCorpusBeforeServingAnswers() throws Exception {
        PolicyCorpus corpus = mock(PolicyCorpus.class);
        ElasticsearchPolicyIndex index = mock(ElasticsearchPolicyIndex.class);
        when(index.rebuild(corpus)).thenReturn(true);

        new PolicyIndexBootstrap(corpus, index).run(null);

        verify(index).rebuild(corpus);
    }

    @Test
    void startupFailsWhenReviewedCorpusCannotBePublished() {
        PolicyCorpus corpus = mock(PolicyCorpus.class);
        ElasticsearchPolicyIndex index = mock(ElasticsearchPolicyIndex.class);
        when(index.rebuild(corpus)).thenReturn(false);

        assertThatThrownBy(() -> new PolicyIndexBootstrap(corpus, index).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("规则索引初始化失败");
    }
}
