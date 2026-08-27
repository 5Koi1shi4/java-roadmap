package com.example.files.application.cleanup;

import java.time.Instant;
import java.util.UUID;

/** 持久化清理任务的只读模型。 */
public record CleanupTask(UUID taskId, CleanupTaskType type, String targetId, long targetGeneration,
                          String objectKey, UUID sourceSessionId, String status, String owner,
                          UUID claimToken, Instant leaseUntil, Instant availableAt, int attemptCount,
                          String lastError) { }
