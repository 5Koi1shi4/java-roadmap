package com.example.search.application.maintenance;

import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchSyncMetrics;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Thin orchestration boundary: preparation and cutover own their algorithms. */
@Service
public class SearchRebuildRunner {
    private final SearchRebuildPreparer preparer;
    private final SearchRebuildCutover cutover;
    private final SearchCoordinationRepository coordination;
    private final RebuildJobRepository jobs;
    private final ElasticsearchIndexManager indexes;
    private final PlatformTransactionManager transactionManager;
    private final SearchSyncMetrics metrics;

    @Autowired
    public SearchRebuildRunner(SearchRebuildPreparer preparer, SearchRebuildCutover cutover,
                               SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                               ElasticsearchIndexManager indexes, PlatformTransactionManager transactionManager,
                               ObjectProvider<SearchSyncMetrics> metricsProvider) {
        this(preparer, cutover, coordination, jobs, indexes, transactionManager,
                metricsProvider.getIfAvailable(SearchSyncMetrics::noop));
    }

    public SearchRebuildRunner(SearchRebuildPreparer preparer, SearchRebuildCutover cutover,
                               SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                               ElasticsearchIndexManager indexes, PlatformTransactionManager transactionManager) {
        this(preparer, cutover, coordination, jobs, indexes, transactionManager, SearchSyncMetrics.noop());
    }

    public SearchRebuildRunner(SearchRebuildPreparer preparer, SearchRebuildCutover cutover,
                               SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                               ElasticsearchIndexManager indexes, PlatformTransactionManager transactionManager,
                               SearchSyncMetrics metrics) {
        this.preparer = Objects.requireNonNull(preparer, "preparer is required");
        this.cutover = Objects.requireNonNull(cutover, "cutover is required");
        this.coordination = Objects.requireNonNull(coordination, "coordination is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.indexes = Objects.requireNonNull(indexes, "indexes is required");
        this.transactionManager = transactionManager;
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    public SearchRebuildRunner(SearchRebuildPreparer preparer, SearchRebuildCutover cutover,
                               SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                               ElasticsearchIndexManager indexes) {
        this(preparer, cutover, coordination, jobs, indexes, null);
    }

    public RebuildJob run(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        RebuildJob job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("unknown rebuild job"));
        Instant started = Instant.now();
        try {
            PreparedRebuild prepared = preparer.prepare(jobId);
            RebuildJob completed = cutover.cutover(prepared);
            metrics.recordRebuild(true, Duration.between(started, Instant.now()), completed.differenceCount());
            return completed;
        } catch (RuntimeException failure) {
            boolean split = false;
            try {
                AliasTargets aliases = indexes.aliasTargets();
                split = aliases.read() != null && aliases.write() != null
                        && !Objects.equals(aliases.read(), aliases.write());
            } catch (RuntimeException ignored) {
                split = true;
            }
            if (!split) {
                try {
                    cleanupFailure(jobId, job.owner(), reason(failure));
                } catch (RuntimeException ignored) { }
            }
            long differences = failure instanceof RebuildValidationException validation
                    ? validation.differenceCount() : 0;
            metrics.recordRebuild(false, Duration.between(started, Instant.now()), differences);
            throw failure;
        }
    }

    private void cleanupFailure(UUID jobId, String owner, String failureReason) {
        writeTx(() -> {
            coordination.lockExclusive();
            jobs.markFailed(jobId, owner, failureReason);
            coordination.clearActiveRebuildId(jobId, owner);
            coordination.setDispatcherPaused(false);
            return null;
        });
    }

    private <T> T writeTx(Supplier<T> action) {
        if (transactionManager == null) return action.get();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
        return tx.execute(status -> action.get());
    }

    private static String reason(RuntimeException failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) value = failure.getClass().getSimpleName();
        value = value.replace("\r", "").replace("\n", "");
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
