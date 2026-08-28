package com.example.files.application.cleanup;

import com.example.files.domain.UploadSession;
import com.example.files.domain.StoredBlob;
import com.example.files.application.upload.BlobReservation;

import java.util.UUID;
import java.time.Duration;
import java.util.List;

/** 清理任务持久化端口；入队操作必须具备目标唯一性以支持重复补偿。 */
public interface CleanupTaskRepository {
    void enqueueTemp(UploadSession session, String tempKey);

    void enqueueTempIfEligible(UUID sessionId);

    /** 为正式对象创建幂等补偿任务，供恢复服务使用。 */
    void enqueueBlob(StoredBlob blob);

    /** 通过已授权领取结果入队，避免调用方读取 Blob 物理元数据。 */
    void enqueueBlob(BlobReservation.Granted reservation);

    /** 以数据库时间原子领取最多 batch 个任务；实现必须写入全新随机 token。 */
    default List<ClaimedCleanup> claimBatch(String owner, int batchSize, Duration lease) {
        throw new UnsupportedOperationException("cleanup claiming is not supported");
    }

    default ClaimedCleanup claimOne(String owner, Duration lease) {
        List<ClaimedCleanup> claimed = claimBatch(owner, 1, lease);
        return claimed.isEmpty() ? null : claimed.get(0);
    }
    default List<ClaimedCleanup> claimBatch(String owner, Duration lease, int batchSize) {
        return claimBatch(owner, batchSize, lease);
    }
    default boolean retry(UUID taskId, UUID claimToken, Duration delay) {
        return retry(taskId, claimToken, delay, null);
    }

    default boolean complete(UUID taskId, UUID claimToken) { throw new UnsupportedOperationException("cleanup completion is not supported"); }
    default boolean retry(UUID taskId, UUID claimToken, Duration delay, String error) { throw new UnsupportedOperationException("cleanup retry is not supported"); }
    default boolean fail(UUID taskId, UUID claimToken, String error) { throw new UnsupportedOperationException("cleanup failure is not supported"); }
    default boolean resetFailed(UUID taskId) { throw new UnsupportedOperationException("cleanup maintenance is not supported"); }
    /** Blob 状态与任务完成必须由实现以同一显式事务发布。 */
    boolean completeBlobAndTask(long blobId, long generation, String objectKey, UUID blobToken,
                                UUID taskId, UUID claimToken);
}
