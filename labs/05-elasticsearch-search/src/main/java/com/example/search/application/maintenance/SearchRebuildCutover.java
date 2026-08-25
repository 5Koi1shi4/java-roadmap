package com.example.search.application.maintenance;

import com.example.search.application.sync.IndexMutation;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchOutboxEvent;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Pauses dispatchers, drains claims, and performs one fenced final cutover transaction. */
@Service
public class SearchRebuildCutover {
    private static final int PAGE_SIZE = 500;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    private final SearchCoordinationRepository coordination;
    private final RebuildJobRepository jobs;
    private final SearchOutboxRepository outbox;
    private final SearchIndexWriter writer;
    private final ElasticsearchIndexManager indexes;
    private final RebuildValidator validator;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public SearchRebuildCutover(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                SearchOutboxRepository outbox, SearchIndexWriter writer,
                                ElasticsearchIndexManager indexes, RebuildValidator validator,
                                PlatformTransactionManager transactionManager) {
        this.coordination = Objects.requireNonNull(coordination, "coordination is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.writer = Objects.requireNonNull(writer, "writer is required");
        this.indexes = Objects.requireNonNull(indexes, "indexes is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.transactionManager = transactionManager;
    }

    public SearchRebuildCutover(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                SearchOutboxRepository outbox, SearchIndexWriter writer,
                                ElasticsearchIndexManager indexes, RebuildValidator validator) {
        this(coordination, jobs, outbox, writer, indexes, validator, null);
    }

    public RebuildJob cutover(PreparedRebuild prepared) {
        if (prepared == null) throw new IllegalArgumentException("prepared rebuild is required");
        RebuildJob original = jobs.find(prepared.jobId()).orElseThrow(() -> new IllegalArgumentException("unknown rebuild job"));
        String owner = original.owner();
        pauseDispatchers(true);
        if (!drain()) {
            failAndRelease(prepared.jobId(), owner, "timed out draining processing outbox", false);
            return jobs.find(prepared.jobId()).orElse(original);
        }
        AliasTargets sourceAliases = null;
        AtomicBoolean aliasSwapAttempted = new AtomicBoolean(false);
        try {
            // Capture and persist the expected source before entering the irreversible phase.
            // Any ambiguity is unsafe and must remain recoverable under pause.
            sourceAliases = inspectAliases();
            if (!sourceAliases.isConfigured() || !Objects.equals(sourceAliases.read(), sourceAliases.write())) {
                throw new SplitAliasException("read/write aliases are split or not configured");
            }
            // Persist CUTOVER in its own committed transaction before any alias request. A
            // crash after this point is therefore discoverable by the recovery worker.
            String sourceIndex = sourceAliases.read();
            if (!writeTx(() -> jobs.markCutover(prepared.jobId(), owner, sourceIndex))) {
                throw new RebuildLeaseLostException("rebuild lease lost before cutover");
            }
            writeTx(() -> {
                // This is deliberately the first lock in the final window. Product writes and claims take it shared.
                coordination.lockExclusive();
                if (!jobs.fenceCutover(prepared.jobId(), owner)
                        || !coordination.activeRebuildId().filter(prepared.jobId()::equals).isPresent()) {
                    throw new RebuildLeaseLostException("rebuild lease or active rebuild fencing failed");
                }
                RebuildJob locked = jobs.findForUpdate(prepared.jobId())
                        .orElseThrow(() -> new IllegalStateException("rebuild job disappeared"));
                if (locked.status() != RebuildStatus.RUNNING || !owner.equals(locked.owner())
                        || locked.phase() != RebuildPhase.CUTOVER) {
                    throw new RebuildLeaseLostException("rebuild owner or status changed");
                }
                AliasTargets aliases = inspectAliases();
                if (!aliases.isConfigured() || !Objects.equals(aliases.read(), aliases.write())
                        || !Objects.equals(locked.sourceIndex(), aliases.read())) {
                    throw new SplitAliasException("read/write aliases no longer match the fenced source index");
                }
                long finalWatermark = outbox.highWatermark();
                replay(prepared.preparedWatermark(), finalWatermark, prepared.targetIndex());
                indexes.refresh(prepared.targetIndex());
                RebuildValidation result = validator.validate(prepared.targetIndex(), finalWatermark);
                if (!result.consistent()) {
                    throw new RebuildValidationException(
                            "rebuild validation differed in " + result.differenceCount() + " products",
                            result.differenceCount());
                }
                if (!jobs.fenceCutover(prepared.jobId(), owner)) {
                    throw new RebuildLeaseLostException("rebuild lease lost immediately before alias swap");
                }
                aliasSwapAttempted.set(true);
                indexes.swapReadWriteAliases(aliases.read(), prepared.targetIndex());
                if (!jobs.markCompleted(prepared.jobId(), owner, finalWatermark, result.differenceCount())
                        || !coordination.clearActiveRebuildId(prepared.jobId(), owner)) {
                    throw new IllegalStateException("could not finalize rebuild job");
                }
                return null;
            });
            pauseDispatchers(false);
            return jobs.find(prepared.jobId()).orElseThrow();
        } catch (RuntimeException failure) {
            boolean preserveCutover = aliasSwapAttempted.get() || failure instanceof SplitAliasException;
            failAndRelease(prepared.jobId(), owner, reason(failure), preserveCutover);
            throw failure;
        }
    }

    private AliasTargets inspectAliases() {
        try {
            return indexes.aliasTargets();
        } catch (SplitAliasException e) {
            throw e;
        } catch (RuntimeException e) {
            // A multi-target/partial alias response is unsafe to classify as an ordinary
            // rebuild failure: leave pause and active_rebuild_id for operator recovery.
            throw new SplitAliasException("could not prove read/write aliases are a single target");
        }
    }

    private void replay(long preparedWatermark, long finalWatermark, String target) {
        long cursor = preparedWatermark;
        while (cursor < finalWatermark) {
            List<SearchOutboxEvent> page = outbox.eventsBetween(cursor, finalWatermark, PAGE_SIZE);
            if (page.isEmpty()) break;
            List<IndexMutation> mutations = page.stream().map(SearchOutboxEvent::snapshot).map(IndexMutation::from).toList();
            List<IndexWriteResult> results = writer.bulkWrite(target, mutations);
            if (results == null || results.size() != mutations.size()) throw new IllegalStateException("incomplete final replay result");
            for (int i = 0; i < mutations.size(); i++) {
                IndexMutation mutation = mutations.get(i);
                IndexWriteResult result = results.get(i);
                if (result == null || result.productId() != mutation.productId() || result.sourceVersion() != mutation.sourceVersion()
                        || (result.outcome() != IndexWriteResult.Outcome.APPLIED && result.outcome() != IndexWriteResult.Outcome.SUPERSEDED)) {
                    throw new IllegalStateException("final replay write did not apply");
                }
            }
            long next = page.get(page.size() - 1).id();
            if (next <= cursor) throw new IllegalStateException("final replay did not advance");
            cursor = next;
        }
    }

    private boolean drain() {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (outbox.hasUnexpiredProcessing()) {
            if (System.nanoTime() >= deadline) return false;
            try { Thread.sleep(POLL_INTERVAL.toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    private void pauseDispatchers(boolean paused) {
        writeTx(() -> { coordination.setDispatcherPaused(paused); return null; });
    }

    private void failAndRelease(UUID jobId, String owner, String reason, boolean preserveCutover) {
        try {
            writeTx(() -> {
                coordination.lockExclusive();
                if (preserveCutover) {
                    // Once an alias request was attempted, the database and Elasticsearch may
                    // disagree. Keep both durable safety signals for recovery; never classify it
                    // as an ordinary failed rebuild or release the dispatcher lock.
                    coordination.setDispatcherPaused(true);
                } else {
                    if (!jobs.markFailed(jobId, owner, reason)) {
                        throw new IllegalStateException("could not mark rebuild failed");
                    }
                    if (!coordination.clearActiveRebuildId(jobId, owner)) {
                        throw new IllegalStateException("could not clear active rebuild");
                    }
                    coordination.setDispatcherPaused(false);
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            // Preserve the original cutover failure; recovery will inspect the durable phase/aliases.
        }
    }

    private <T> T writeTx(Supplier<T> action) {
        if (transactionManager == null) return action.get();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
        return tx.execute(status -> action.get());
    }

    private static String reason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        message = message.replace("\r", "").replace("\n", "");
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
