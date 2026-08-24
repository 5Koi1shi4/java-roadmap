package com.example.search.application.maintenance;

import com.example.search.application.product.ProductRepository;
import com.example.search.application.sync.IndexMutation;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchOutboxEvent;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.Product;
import com.example.search.domain.ProductSearchSnapshot;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Builds a physical index from one repeatable-read product snapshot and a bounded outbox replay. */
@Service
public class SearchRebuildPreparer {
    private static final int PAGE_SIZE = 500;
    private static final long LEASE_SECONDS = 30;

    private final SearchCoordinationRepository coordination;
    private final RebuildJobRepository jobs;
    private final ProductRepository products;
    private final SearchOutboxRepository outbox;
    private final SearchIndexWriter writer;
    private final ElasticsearchIndexManager indexes;
    private final RebuildProgressListener progress;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public SearchRebuildPreparer(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                 ProductRepository products, SearchOutboxRepository outbox,
                                 SearchIndexWriter writer, ElasticsearchIndexManager indexes,
                                 PlatformTransactionManager transactionManager,
                                 ObjectProvider<RebuildProgressListener> progressProvider) {
        this(coordination, jobs, products, outbox, writer, indexes, transactionManager,
                progressProvider == null ? RebuildProgressListener.NOOP
                        : progressProvider.getIfAvailable(() -> RebuildProgressListener.NOOP));
    }

    public SearchRebuildPreparer(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                 ProductRepository products, SearchOutboxRepository outbox,
                                 SearchIndexWriter writer, ElasticsearchIndexManager indexes,
                                 PlatformTransactionManager transactionManager,
                                 RebuildProgressListener progress) {
        this.coordination = Objects.requireNonNull(coordination, "coordination is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.products = Objects.requireNonNull(products, "products is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.writer = Objects.requireNonNull(writer, "writer is required");
        this.indexes = Objects.requireNonNull(indexes, "indexes is required");
        this.transactionManager = transactionManager;
        this.progress = progress == null ? RebuildProgressListener.NOOP : progress;
    }

    public SearchRebuildPreparer(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                 ProductRepository products, SearchOutboxRepository outbox,
                                 SearchIndexWriter writer, ElasticsearchIndexManager indexes,
                                 RebuildProgressListener progress) {
        this(coordination, jobs, products, outbox, writer, indexes, null, progress);
    }

    public SearchRebuildPreparer(SearchCoordinationRepository coordination, RebuildJobRepository jobs,
                                 ProductRepository products, SearchOutboxRepository outbox,
                                 SearchIndexWriter writer, ElasticsearchIndexManager indexes) {
        this(coordination, jobs, products, outbox, writer, indexes, null, RebuildProgressListener.NOOP);
    }

    public UUID start(String owner) {
        validateOwner(owner);
        UUID jobId = UUID.randomUUID();
        String target = indexes.physicalIndexName(jobId);
        writeTx(() -> {
            coordination.lockExclusive();
            coordination.activeRebuildId().ifPresent(active -> {
                RebuildJob activeJob = jobs.findForUpdate(active).orElse(null);
                if (activeJob != null && jobs.hasValidLeaseForUpdate(active)
                        && (activeJob.status() == RebuildStatus.RUNNING || activeJob.status() == RebuildStatus.PENDING)) {
                    throw new RebuildAlreadyRunningException("rebuild " + active + " is still leased");
                }
                coordination.clearActiveRebuildId(active);
            });

            Instant now = Instant.now();
            RebuildJob job = new RebuildJob(jobId, target, RebuildStatus.PENDING, RebuildPhase.CREATED,
                    owner, now.plusSeconds(LEASE_SECONDS), null, null, 0, 0, null, now, null);
            jobs.insert(job);
            coordination.setActiveRebuildId(jobId);
            return null;
        });
        try {
            indexes.createPhysicalIndex(jobId);
            writeTx(() -> {
                coordination.lockExclusive();
                if (!coordination.activeRebuildId().filter(jobId::equals).isPresent()
                        || !jobs.findForUpdate(jobId).isPresent()
                        || !jobs.markRunning(jobId, owner, leaseFromNow())) {
                    throw new RebuildLeaseLostException("could not acquire rebuild lease " + jobId);
                }
                return null;
            });
            return jobId;
        } catch (RuntimeException failure) {
            writeTx(() -> {
                coordination.lockExclusive();
                jobs.markFailed(jobId, owner, reason(failure));
                coordination.clearActiveRebuildId(jobId);
                return null;
            });
            throw failure;
        }
    }

    public PreparedRebuild prepare(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        RebuildJob job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("unknown rebuild job"));
        if (job.status() != RebuildStatus.RUNNING) throw new IllegalStateException("rebuild is not running");
        String owner = job.owner();
        try {
            long start = writeTx(() -> {
                long watermark = outbox.highWatermark();
                if (!jobs.setStartWatermark(jobId, owner, watermark)) {
                    throw new RebuildLeaseLostException("rebuild lease lost while recording start watermark");
                }
                if (!jobs.renewLease(jobId, owner, leaseFromNow())) {
                    throw new RebuildLeaseLostException("rebuild lease lost before snapshot");
                }
                return watermark;
            });

            SnapshotResult snapshot = snapshotTransaction(jobId, owner, start, job.targetIndex());
            long prepared = writeTx(outbox::highWatermark);
            long imported = writeTx(() -> {
                if (!jobs.setCatchUp(jobId, owner, prepared, snapshot.importedCount())) {
                    throw new RebuildLeaseLostException("rebuild lease lost before catch-up");
                }
                return snapshot.importedCount();
            });
            replayCatchUp(jobId, owner, job.targetIndex(), start, prepared, imported);
            return new PreparedRebuild(jobId, job.targetIndex(), start, prepared,
                    jobs.find(jobId).map(RebuildJob::importedCount).orElse(imported));
        } catch (RuntimeException failure) {
            writeTx(() -> {
                coordination.lockExclusive();
                jobs.markFailed(jobId, owner, reason(failure));
                return null;
            });
            throw failure;
        }
    }

    private SnapshotResult snapshotTransaction(UUID jobId, String owner, long watermark, String target) {
        return snapshotTx(() -> {
            long imported = 0;
            long lastId = 0;
            List<Product> page = products.findPageAfter(lastId, PAGE_SIZE);
            // The first read establishes the InnoDB repeatable-read view before the test hook can commit.
            progress.afterStartWatermark(jobId, watermark);
            while (!page.isEmpty()) {
                List<IndexMutation> mutations = page.stream()
                        .map(ProductSearchSnapshot::from).map(IndexMutation::from).toList();
                renewOrThrow(jobId, owner);
                verifyWrites(target, mutations);
                imported += page.size();
                lastId = page.get(page.size() - 1).id();
                renewOrThrow(jobId, owner);
                page = products.findPageAfter(lastId, PAGE_SIZE);
            }
            renewOrThrow(jobId, owner);
            return new SnapshotResult(imported);
        });
    }

    private void replayCatchUp(UUID jobId, String owner, String target, long start, long prepared, long imported) {
        long lastId = start;
        long total = imported;
        while (lastId < prepared) {
            List<SearchOutboxEvent> page = outbox.eventsBetween(lastId, prepared, PAGE_SIZE);
            if (page.isEmpty()) break;
            List<IndexMutation> mutations = page.stream().map(SearchOutboxEvent::snapshot)
                    .map(IndexMutation::from).toList();
            renewOrThrow(jobId, owner);
            verifyWrites(target, mutations);
            total += page.size();
            long pageLast = page.get(page.size() - 1).id();
            if (pageLast <= lastId) throw new IllegalStateException("outbox replay did not advance");
            lastId = pageLast;
            long progressCount = total;
            writeTx(() -> {
                if (!jobs.addImportedCount(jobId, owner, page.size())
                        || !jobs.renewLease(jobId, owner, leaseFromNow())) {
                    throw new RebuildLeaseLostException("rebuild lease lost during catch-up");
                }
                return progressCount;
            });
        }
        renewOrThrow(jobId, owner);
    }

    private void verifyWrites(String target, List<IndexMutation> mutations) {
        List<IndexWriteResult> results = writer.bulkWrite(target, mutations);
        if (results == null || results.size() != mutations.size()) {
            throw new IllegalStateException("bulk writer returned incomplete results");
        }
        for (int i = 0; i < mutations.size(); i++) {
            IndexMutation mutation = mutations.get(i);
            IndexWriteResult result = results.get(i);
            if (result == null || result.outcome() == null
                    || result.productId() != mutation.productId()
                    || result.sourceVersion() != mutation.sourceVersion()
                    || (result.outcome() != IndexWriteResult.Outcome.APPLIED
                    && result.outcome() != IndexWriteResult.Outcome.SUPERSEDED)) {
                throw new IllegalStateException("rebuild index write did not apply");
            }
        }
    }

    private void renewOrThrow(UUID jobId, String owner) {
        if (!writeTx(() -> jobs.renewLease(jobId, owner, leaseFromNow()))) {
            throw new RebuildLeaseLostException("rebuild lease lost for " + jobId);
        }
    }

    private Instant leaseFromNow() { return Instant.now().plusSeconds(LEASE_SECONDS); }

    private <T> T writeTx(Supplier<T> action) {
        if (transactionManager == null) return action.get();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> action.get());
    }

    private <T> T snapshotTx(Supplier<T> action) {
        if (transactionManager == null) return action.get();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        return tx.execute(status -> action.get());
    }

    private static void validateOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1 to 128 characters");
        }
    }

    private static String reason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null) message = failure.getClass().getSimpleName();
        return message.replace("\r", "").replace("\n", "");
    }

    private record SnapshotResult(long importedCount) { }
}
