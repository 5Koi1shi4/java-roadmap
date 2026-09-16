package com.example.campusmarket.product.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Replay 的有界完成屏障。它是读侧协议模型，不依赖市场服务的 Java 类型。
 */
public record ProductReplayCompleteEvent(
    String eventType,
    int schemaVersion,
    UUID replayId,
    long sourceHighWatermark,
    Instant completedAt
) {
    public static final String EVENT_TYPE = "PRODUCT_REPLAY_COMPLETE";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ProductReplayCompleteEvent {
        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("replay 完成事件类型无效");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("replay 完成事件版本不支持");
        }
        Objects.requireNonNull(replayId, "replay ID 不能为空");
        if (sourceHighWatermark < 0) {
            throw new IllegalArgumentException("源高水位不能为负数");
        }
        Objects.requireNonNull(completedAt, "replay 完成时间不能为空");
    }
}
