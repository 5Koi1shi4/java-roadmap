package com.example.campusmarket.product.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** replay 完成控制消息必须严格遵守独立的 schema=1 契约。 */
class ProductReplayCompleteDecoderTest {
    private static final UUID REPLAY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final ProductReplayCompleteDecoder decoder = new ProductReplayCompleteDecoder();

    @Test
    void decodesReplayCompleteMessageWithZeroHighWatermark() {
        Optional<ProductReplayCompleteEvent> result = decoder.decodeIfPresent(message(
            "{\"eventType\":\"PRODUCT_REPLAY_COMPLETE\",\"schemaVersion\":1,"
                + "\"replayId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                + "\"sourceHighWatermark\":0,\"completedAt\":\"2026-09-16T00:00:00Z\"}"));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().eventType()).isEqualTo("PRODUCT_REPLAY_COMPLETE");
        assertThat(result.orElseThrow().schemaVersion()).isEqualTo(1);
        assertThat(result.orElseThrow().replayId()).isEqualTo(REPLAY_ID);
        assertThat(result.orElseThrow().sourceHighWatermark()).isZero();
        assertThat(result.orElseThrow().completedAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
    }

    @Test
    void ignoresSnapshotEventForControlDecoder() {
        Optional<ProductReplayCompleteEvent> result = decoder.decodeIfPresent(message(
            "{\"eventId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\","
                + "\"listingId\":\"cccccccc-cccc-cccc-cccc-cccccccccccc\","
                + "\"aggregateVersion\":1,\"eventType\":\"LISTING_PUBLISHED\","
                + "\"occurredAt\":\"2026-09-16T00:00:00Z\",\"schemaVersion\":2,"
                + "\"snapshot\":{\"title\":\"教材\",\"description\":\"描述\","
                + "\"category\":\"教材\",\"unitPriceFen\":100,"
                + "\"availableQuantity\":1,\"status\":\"ON_SALE\"}}"));

        assertThat(result).isEmpty();
    }

    @Test
    void rejectsUnknownControlField() {
        assertThatThrownBy(() -> decoder.decodeIfPresent(message(
            "{\"eventType\":\"PRODUCT_REPLAY_COMPLETE\",\"schemaVersion\":1,"
                + "\"replayId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                + "\"sourceHighWatermark\":1,\"completedAt\":\"2026-09-16T00:00:00Z\","
                + "\"unexpected\":true}")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedVersionNegativeWatermarkAndNonUtcTime() {
        assertThatThrownBy(() -> decoder.decodeIfPresent(message(
            "{\"eventType\":\"PRODUCT_REPLAY_COMPLETE\",\"schemaVersion\":2,"
                + "\"replayId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                + "\"sourceHighWatermark\":1,\"completedAt\":\"2026-09-16T00:00:00Z\"}")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decodeIfPresent(message(
            "{\"eventType\":\"PRODUCT_REPLAY_COMPLETE\",\"schemaVersion\":1,"
                + "\"replayId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                + "\"sourceHighWatermark\":-1,\"completedAt\":\"2026-09-16T00:00:00Z\"}")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decodeIfPresent(message(
            "{\"eventType\":\"PRODUCT_REPLAY_COMPLETE\",\"schemaVersion\":1,"
                + "\"replayId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                + "\"sourceHighWatermark\":1,\"completedAt\":\"2026-09-16T08:00:00+08:00\"}")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] message(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
