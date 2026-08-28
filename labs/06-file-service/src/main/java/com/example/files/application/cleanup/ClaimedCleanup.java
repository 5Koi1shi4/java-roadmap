package com.example.files.application.cleanup;

import java.util.UUID;

/** 已领取任务；claim token 是完成、重试和失败的 fencing 凭据。 */
public record ClaimedCleanup(UUID taskId, CleanupTaskType type, String targetId, long targetGeneration,
                             String objectKey, UUID claimToken, int attemptCount) {
    public ClaimedCleanup {
        if (taskId == null || type == null || targetId == null || targetId.isBlank()
            || targetId.contains("/") || targetId.contains("\\") || hasControl(targetId)
            || targetGeneration <= 0 || objectKey == null || objectKey.isBlank()
            || (!objectKey.startsWith("tmp/") && !objectKey.startsWith("blobs/"))
            || objectKey.endsWith("/") || objectKey.contains("..") || objectKey.contains("\\")
            || objectKey.startsWith("/") || hasControl(objectKey) || claimToken == null) {
            throw new IllegalArgumentException("invalid claimed cleanup");
        }
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
