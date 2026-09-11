package com.example.campusmarket.catalog.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 在调用方事务之外领取清理任务，并与别名切换串行化删除。 */
@Service
@Profile("!test")
public class SearchIndexCleanupWorker {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);

    private final SearchIndexCleanupRepository repository;
    private final SearchAliasCoordinator coordinator;
    private final ElasticsearchProductSearch elasticsearch;
    private final MeterRegistry metrics;
    private volatile Consumer<String> cleanupDeleteHook = ignored -> { };

    public SearchIndexCleanupWorker(SearchIndexCleanupRepository repository,
                                    SearchAliasCoordinator coordinator,
                                    ElasticsearchProductSearch elasticsearch) {
        this(repository, coordinator, elasticsearch, new SimpleMeterRegistry());
    }

    @Autowired
    public SearchIndexCleanupWorker(SearchIndexCleanupRepository repository,
                                    SearchAliasCoordinator coordinator,
                                    ElasticsearchProductSearch elasticsearch, MeterRegistry metrics) {
        this.repository = Objects.requireNonNull(repository, "清理仓储不能为空");
        this.coordinator = Objects.requireNonNull(coordinator, "别名协调器不能为空");
        this.elasticsearch = Objects.requireNonNull(elasticsearch, "Elasticsearch不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标注册表不能为空");
    }

    /** 在 live 检查后、外部删除前调用的测试接缝。 */
    public void setCleanupDeleteHook(Consumer<String> hook) {
        cleanupDeleteHook = hook == null ? ignored -> { } : hook;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runOnce() {
        List<SearchIndexCleanupRepository.CleanupClaim> claims = repository.claimBatch(20);
        for (SearchIndexCleanupRepository.CleanupClaim claim : claims) cleanupOne(claim);
        return claims.size();
    }

    private void cleanupOne(SearchIndexCleanupRepository.CleanupClaim claim) {
        try {
            coordinator.execute(COORDINATION_TIMEOUT, "cleanup-delete", claim.owner(), connection -> {
                if (!repository.owned(connection, claim)) return null;
                Set<String> live = elasticsearch.readAllAliasMembers();
                if (live.contains(claim.indexName())) {
                    metrics.counter("search.alias.cleanup.live.protected").increment();
                    repository.completeProtected(connection, claim);
                    return null;
                }
                cleanupDeleteHook.accept(claim.indexName());
                elasticsearch.deleteIndex(claim.indexName());
                repository.complete(connection, claim);
                return null;
            });
        } catch (SearchAliasCoordinator.SearchCoordinationTimeoutException timeout) {
            repository.retryAfterCoordinationFailure(claim, timeout);
        } catch (RuntimeException failure) {
            repository.fail(claim, failure);
        }
    }
}
