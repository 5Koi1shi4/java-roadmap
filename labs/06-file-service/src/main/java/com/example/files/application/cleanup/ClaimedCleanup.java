package com.example.files.application.cleanup;

import java.util.UUID;

/** 已领取任务；claim token 是完成、重试和失败的 fencing 凭据。 */
public record ClaimedCleanup(UUID taskId, CleanupTaskType type, String targetId, long targetGeneration,
                             String objectKey, UUID claimToken, int attemptCount) {
    public ClaimedCleanup {
        if (taskId == null || type == null || targetId == null || objectKey == null || claimToken == null) {
            throw new IllegalArgumentException("invalid claimed cleanup");
        }
    }
}
