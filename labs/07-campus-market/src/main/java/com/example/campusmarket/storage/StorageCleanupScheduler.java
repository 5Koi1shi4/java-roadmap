package com.example.campusmarket.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.CannotAcquireLockException;
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

    public StorageCleanupScheduler(JdbcTemplate jdbc, PrivateObjectStorage storage,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("批量大小必须在1到100之间");
        List<Task> tasks = claim(limit);
        int completed = 0;
        for (Task task : tasks) {
            try {
                storage.delete(task.objectKey());
                complete(task);
                completed++;
            } catch (MinioPrivateObjectStorage.ObjectNotFoundException e) {
                complete(task);
                completed++;
            } catch (MinioPrivateObjectStorage.StorageUnavailableException e) {
                release(task, "TRANSIENT");
            } catch (RuntimeException e) {
                release(task, "UNKNOWN");
            }
        }
        return completed;
    }

    protected List<Task> claimInternal(int limit) {
        String owner = "cleanup-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO storage_cleanup_task (id, cleanup_business_key, object_key, status, run_after, created_at, updated_at)
            SELECT UUID(), CONCAT('listing-upload:', id), object_key, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            FROM object_upload_session
            WHERE purpose='LISTING_MEDIA' AND status IN ('OPEN','ABORTED') AND expires_at <= CURRENT_TIMESTAMP(6)
            ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP(6)
            """);
        jdbc.update("UPDATE object_upload_session SET status='EXPIRED', updated_at=CURRENT_TIMESTAMP(6) WHERE purpose='LISTING_MEDIA' AND status='OPEN' AND expires_at <= CURRENT_TIMESTAMP(6)");
        List<Candidate> candidates = jdbc.query("""
            SELECT id, object_key FROM storage_cleanup_task
            WHERE (status='PENDING' AND run_after <= CURRENT_TIMESTAMP(6))
               OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
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
            } catch (CannotAcquireLockException e) {
                if (attempt == 1) return List.of();
            }
        }
        return List.of();
    }

    protected void complete(Task task) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE storage_cleanup_task SET status='COMPLETED', lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            task.id(), task.owner(), task.token()));
    }

    protected void release(Task task, String failureClass) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE storage_cleanup_task SET status='PENDING', failure_class=?, lease_until=NULL, run_after=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND), updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            failureClass, task.id(), task.owner(), task.token()));
    }

    private record Candidate(String id, String objectKey) { }
    private record Task(String id, String objectKey, String owner, String token) { }
}
