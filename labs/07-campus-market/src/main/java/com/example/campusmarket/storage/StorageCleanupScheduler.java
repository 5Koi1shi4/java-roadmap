package com.example.campusmarket.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
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
        if (limit <= 0) throw new IllegalArgumentException("批量大小必须为正数");
        List<Task> tasks = transactions.execute(status -> claimInternal(limit));
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
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("UPDATE storage_cleanup_task SET status='PROCESSING', owner_id=?, claim_token=?, lease_until=?, attempt_count=attempt_count+1, updated_at=? WHERE status='PROCESSING' AND lease_until < ?",
            owner, token, Timestamp.from(Instant.now().plusSeconds(60)), now, now);
        return jdbc.query("SELECT id, object_key, owner_id, claim_token FROM storage_cleanup_task WHERE status='PENDING' AND run_after <= ? ORDER BY run_after LIMIT " + limit,
            (rs, rowNum) -> {
                String id = rs.getString("id");
                int n = jdbc.update("UPDATE storage_cleanup_task SET status='PROCESSING', owner_id=?, claim_token=?, lease_until=?, attempt_count=attempt_count+1, updated_at=? WHERE id=? AND status='PENDING'",
                    owner, token, Timestamp.from(Instant.now().plusSeconds(60)), now, id);
                return n == 1 ? new Task(id, rs.getString("object_key"), owner, token) : null;
            }, now).stream().filter(java.util.Objects::nonNull).toList();
    }

    protected void complete(Task task) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE storage_cleanup_task SET status='COMPLETED', lease_until=NULL, updated_at=? WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            Timestamp.from(Instant.now()), task.id(), task.owner(), task.token()));
    }

    protected void release(Task task, String failureClass) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE storage_cleanup_task SET status='PENDING', failure_class=?, lease_until=NULL, run_after=?, updated_at=? WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?",
            failureClass, Timestamp.from(Instant.now().plusSeconds(30)), Timestamp.from(Instant.now()), task.id(), task.owner(), task.token()));
    }

    private record Task(String id, String objectKey, String owner, String token) { }
}
