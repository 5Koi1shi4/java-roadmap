package com.example.campusmarket.product;

import com.example.campusmarket.product.event.ProductSnapshotDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 商品事件 JSON 的协议边界；读服务不得依赖市场服务的 Java 模型。 */
class ProductEventContractTest {
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LISTING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ProductSnapshotDecoder decoder = new ProductSnapshotDecoder();

    @Test
    void acceptsVersionTwoCompleteSnapshotWithoutSourceModuleClasses() {
        var event = decoder.decode(bytes(validEvent()));

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.listingId()).isEqualTo(LISTING_ID);
        assertThat(event.aggregateVersion()).isEqualTo(3);
        assertThat(event.eventType()).isEqualTo("LISTING_PUBLISHED");
        assertThat(event.schemaVersion()).isEqualTo(2);
        assertThat(event.snapshot().title()).isEqualTo("并发编程教材");
        assertThat(event.snapshot().availableQuantity()).isEqualTo(2);
        assertThat(event.snapshot().status()).isEqualTo("ON_SALE");
    }

    @Test
    void rejectsUnknownFieldInsteadOfSilentlyIgnoringIt() {
        String unknown = validEvent().replace("\"schemaVersion\":2,", "\"schemaVersion\":2,\"privateObjectKey\":\"secret\",");

        assertThatThrownBy(() -> decoder.decode(bytes(unknown)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedVersionAndNegativeStock() {
        assertThatThrownBy(() -> decoder.decode(bytes(validEvent().replace("\"schemaVersion\":2", "\"schemaVersion\":3"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode(bytes(validEvent().replace("\"availableQuantity\":2", "\"availableQuantity\":-1"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownEventTypeStatusAndWrongScalarType() {
        assertThatThrownBy(() -> decoder.decode(bytes(validEvent().replace("LISTING_PUBLISHED", "UNKNOWN_EVENT"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode(bytes(validEvent().replace("\"status\":\"ON_SALE\"", "\"status\":\"EXPIRED\""))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode(bytes(validEvent().replace("\"aggregateVersion\":3", "\"aggregateVersion\":\"3\""))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String validEvent() {
        return """
            {"eventId":"11111111-1111-1111-1111-111111111111",
             "listingId":"22222222-2222-2222-2222-222222222222",
             "aggregateVersion":3,"eventType":"LISTING_PUBLISHED",
             "occurredAt":"2026-09-15T00:00:00Z","schemaVersion":2,
             "snapshot":{"title":"并发编程教材","description":"九成新，完整教材",
                         "category":"教材","unitPriceFen":12900,
                         "availableQuantity":2,"status":"ON_SALE"}}
            """;
    }
}
