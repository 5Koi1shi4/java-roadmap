package com.example.campusmarket.storage;

import com.example.campusmarket.observability.CampusMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class StorageCleanupScheduler {
    private final JdbcTemplate jdbc;
    private final PrivateObjectStorage storage;
    private final TransactionTemplate transactions;
    private final CampusMetrics metrics;

    public StorageCleanupScheduler(JdbcTemplate jdbc, PrivateObjectStorage storage,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(jdbc, storage, transactionManager, null);
    }

    @Autowired
    public StorageCleanupScheduler(JdbcTemplate jdbc, PrivateObjectStorage storage,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager,
                                  CampusMetrics metrics) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("批量大小必须在1到100之间");
        List<Task> tasks = claim(limit);
        int completed = 0;
        for (Task task : tasks) {
            long started = System.nanoTime();
            try {
                storage.delete(task.objectKey());
                if (complete(task)) { recordStorage("SUCCESS", started); completed++; }
            } catch (MinioPrivateObjectStorage.ObjectNotFoundException e) {
                if (complete(task)) { recordStorage("SUCCESS", started); completed++; }
            } catch (MinioPrivateObjectStorage.StorageUnavailableException e) {
                if (release(task, "TRANSIENT")) recordStorage("TIMEOUT", started);
            } catch (RuntimeException e) {
                if (release(task, "UNKNOWN")) recordStorage("FAILURE", started);
            }
        }
        return completed;
    }

    private void recordStorage(String result, long started) {
        if (metrics == null) return;
        metrics.recordStorage(result);
        metrics.recordOperationDuration("DELETE", java.time.Duration.ofNanos(System.nanoTime() - started));
    }

    protected List<Task> claimInternal(int limit) {
        String owner = "cleanup-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO storage_cleanup_task (id, cleanup_business_key, object_key, status, run_after, created_at, updated_at)
            SELECT UUID(), CASE WHEN purpose='LISTING_MEDIA' THEN CONCAT('listing-upload:', id) ELSE CONCAT('dispute-upload:', id) END, object_key, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            FROM object_upload_session
            WHERE purpose IN ('LISTING_MEDIA','DISPUTE_EVIDENCE') AND status IN ('OPEN','ABORTED') AND expires_at <= CURRENT_TIMESTAMP(6)
            ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP(6)
            """);
        jdbc.update("UPDATE object_upload_session SET status='EXPIRED', updated_at=CURRENT_TIMESTAMP(6) WHERE purpose IN ('LISTING_MEDIA','DISPUTE_EVIDENCE') AND status='OPEN' AND expires_at <= CURRENT_TIMESTAMP(6)");
        List<Candidate> candidates = jdbc.query("""
            SELECT id, object_key FROM storage_cleanup_task
            WHERE (status='PENDING' AND run_after <= CURRENT_TIMESTAMP(6))
               OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY run_after, id LIMIT ? FOR UPDATE SKIP LOCKED
            """, (rs, rowNum) -> new Candidate(rs.getString("id"), rs.getString("object_key")), limit);
        List<Task> claimed = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            int n = jdbc.update("""
                UPDATE storage_cleanup_task SET status='PROCESSING', owner_id=?, claim_token=?
                    , lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND)
                    , attempt_count=attempt_count+1, updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND ((status='PENDING' AND run_after <= CURRENT_TIMESTAMP(6))
                    OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6)))
                """, owner, token, candidate.id());
            if (n == 1) claimed.add(new Task(candidate.id(), candidate.objectKey(), owner, token));
        }
        return claimed;
    }

    private List<Task> claim(int limit) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                List<Task> tasks = transactions.execute(status -> claimInternal(limit));
                return tasks == null ? List.of() : tasks;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == 1) return List.of();
            }
        }
        return List.of();
    }

    protected boolean complete(Task task) {
        Integer changed = transactions.execute(status -> jdbc.update("UPDATE storage_cleanup_task SET status='COMPLETED', lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            task.id(), task.owner(), task.token()));
        return changed != null && changed == 1;
    }

    protected boolean release(Task task, String failureClass) {
        Integer changed = transactions.execute(status -> jdbc.update("UPDATE storage_cleanup_task SET status='PENDING', failure_class=?, lease_until=NULL, run_after=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND), updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            failureClass, task.id(), task.owner(), task.token()));
        return changed != null && changed == 1;
    }

    private record Candidate(String id, String objectKey) { }
    private record Task(String id, String objectKey, String owner, String token) { }
}
