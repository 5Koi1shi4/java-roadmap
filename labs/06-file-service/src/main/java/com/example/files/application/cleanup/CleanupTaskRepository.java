package com.example.files.application.cleanup;

import com.example.files.domain.UploadSession;
import com.example.files.domain.StoredBlob;
import com.example.files.application.upload.BlobReservation;

import java.util.UUID;

/** 清理任务持久化端口；入队操作必须具备目标唯一性以支持重复补偿。 */
public interface CleanupTaskRepository {
    void enqueueTemp(UploadSession session, String tempKey);

    void enqueueTempIfEligible(UUID sessionId);

    /** 为正式对象创建幂等补偿任务，供恢复服务使用。 */
    void enqueueBlob(StoredBlob blob);

    /** 通过已授权领取结果入队，避免调用方读取 Blob 物理元数据。 */
    void enqueueBlob(BlobReservation.Granted reservation);
}
