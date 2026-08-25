package com.example.search.application.maintenance;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Minimal asynchronous rebuild facade used by the maintenance API. */
@Service
public class SearchRebuildService {
    private final SearchRebuildPreparer preparer;
    private final SearchRebuildRunner runner;
    private final RebuildJobRepository jobs;
    private final Executor executor;

    public SearchRebuildService(SearchRebuildPreparer preparer, SearchRebuildRunner runner,
                                RebuildJobRepository jobs,
                                @Qualifier("searchRebuildExecutor") Executor executor) {
        this.preparer = Objects.requireNonNull(preparer, "preparer is required");
        this.runner = Objects.requireNonNull(runner, "runner is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
    }

    public UUID startRebuild() {
        String owner = "search-rebuild-" + UUID.randomUUID();
        UUID jobId = preparer.start(owner);
        executor.execute(() -> {
            try {
                runner.run(jobId);
            } catch (RuntimeException ignored) {
                // Runner persists FAILED or leaves split aliases paused for recovery.
            }
        });
        return jobId;
    }

    public RebuildJob getRebuild(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        return jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("unknown rebuild job"));
    }
}
