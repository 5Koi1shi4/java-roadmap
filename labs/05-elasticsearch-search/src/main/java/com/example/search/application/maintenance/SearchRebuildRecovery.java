package com.example.search.application.maintenance;

import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Conservative, idempotent startup recovery for jobs interrupted around alias cutover. */
@Service
public class SearchRebuildRecovery {
    private final SearchCoordinationRepository coordination;
    private final RebuildJobRepository jobs;
    private final ElasticsearchIndexManager indexes;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public SearchRebuildRecovery(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                 ElasticsearchIndexManager indexes, PlatformTransactionManager transactionManager) {
        this.coordination = Objects.requireNonNull(coordination, "coordination is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.indexes = Objects.requireNonNull(indexes, "indexes is required");
        this.transactionManager = transactionManager;
    }

    public SearchRebuildRecovery(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                 ElasticsearchIndexManager indexes) {
        this(coordination, jobs, indexes, null);
    }

    public void recoverInterruptedCutover() {
        for (RebuildJob job : jobs.findInterrupted()) {
            recover(job);
        }
    }

    private void recover(RebuildJob job) {
        AliasTargets aliases;
        try {
            aliases = indexes.aliasTargets();
        } catch (RuntimeException split) {
            markSplit(job, split.getMessage());
            return;
        }
        boolean target = aliases.isConfigured() && job.targetIndex().equals(aliases.read())
                && job.targetIndex().equals(aliases.write());
        // Only a configured, single physical alias target is safe to classify as the old side.
        // Missing aliases are an unsafe/unknown state and must retain the pause signal.
        boolean old = !target && job.sourceIndex() != null && aliases.isConfigured()
                && job.sourceIndex().equals(aliases.read()) && job.sourceIndex().equals(aliases.write());
        if (target) {
            writeTx(() -> {
                coordination.lockExclusive();
                if (!jobs.renewCutoverLeaseForRecovery(job.jobId(), job.owner())) {
                    throw new IllegalStateException("could not renew interrupted cutover lease");
                }
                if (!jobs.markCompleted(job.jobId(), job.owner())) {
                    throw new IllegalStateException("could not mark recovered rebuild completed");
                }
                if (!coordination.clearActiveRebuildId(job.jobId(), job.owner())) {
                    throw new IllegalStateException("could not clear recovered active rebuild");
                }
                coordination.setDispatcherPaused(false);
                return null;
            });
        } else if (old) {
            writeTx(() -> {
                coordination.lockExclusive();
                if (!jobs.markFailed(job.jobId(), job.owner(), "rebuild interrupted before alias cutover")) {
                    throw new IllegalStateException("could not mark recovered rebuild failed");
                }
                if (!coordination.clearActiveRebuildId(job.jobId(), job.owner())) {
                    throw new IllegalStateException("could not clear recovered active rebuild");
                }
                coordination.setDispatcherPaused(false);
                return null;
            });
        } else {
            markSplit(job, "read/write aliases are split");
        }
    }

    private void markSplit(RebuildJob job, String reason) {
        writeTx(() -> {
            coordination.lockExclusive();
            if (!jobs.markFailed(job.jobId(), job.owner(), reason == null ? "split aliases" : reason)) {
                throw new IllegalStateException("could not record unsafe cutover state");
            }
            // Keep pause and active_rebuild_id as durable safety signals for operator intervention.
            coordination.setDispatcherPaused(true);
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
}
