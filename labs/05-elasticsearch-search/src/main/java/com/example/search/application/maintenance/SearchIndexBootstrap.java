package com.example.search.application.maintenance;

import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the initial physical index and aliases under the database fence. */
@Service
public class SearchIndexBootstrap {
    private final SearchCoordinationRepository coordination;
    private final ElasticsearchIndexManager indexes;

    public SearchIndexBootstrap(SearchCoordinationRepository coordination,
                                ElasticsearchIndexManager indexes) {
        this.coordination = coordination;
        this.indexes = indexes;
    }

    @Transactional
    public AliasTargets ensureInitialized() {
        coordination.lockExclusive();
        AliasTargets targets = indexes.aliasTargets();
        if (targets.isConfigured()) {
            if (!targets.read().equals(targets.write())) {
                throw new IllegalStateException("products aliases point to different physical indices");
            }
            return targets;
        }
        String physical = indexes.createBootstrapIndex();
        indexes.installAliases(physical);
        return new AliasTargets(physical, physical);
    }
}
