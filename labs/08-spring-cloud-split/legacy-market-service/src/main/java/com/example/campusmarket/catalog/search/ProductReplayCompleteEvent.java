package com.example.campusmarket.catalog.search;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 源高水位内的保留快照均已确认入队后发送的投影完成屏障。 */
public record ProductReplayCompleteEvent(int schemaVersion, String eventType, UUID replayId,
                                         long sourceHighWatermark, Instant completedAt) {
    public static final int VERSION = 1;
    public static final String TYPE = "PRODUCT_REPLAY_COMPLETE";

    public ProductReplayCompleteEvent {
        if (schemaVersion != VERSION || !TYPE.equals(eventType)
                || replayId == null || sourceHighWatermark < 0 || completedAt == null) {
            throw new IllegalArgumentException("商品 replay 完成屏障无效");
        }
    }

    public static ProductReplayCompleteEvent of(UUID replayId, long highWatermark) {
        return new ProductReplayCompleteEvent(VERSION, TYPE,
                Objects.requireNonNull(replayId, "replay ID不能为空"), highWatermark, Instant.now());
    }
}
