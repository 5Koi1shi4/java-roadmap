package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchContractTest {
    @Test
    void rejectsUnknownEventAndStatusBeforePersistence() {
        assertThatThrownBy(() -> SearchSchema.requireEventType("UNKNOWN"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductSearchPort.ProductDocument("id", "t", "d", "c", 1, 1,
            "UNKNOWN", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOffsetPaginationAndAcceptsStrictCursor() {
        assertThatThrownBy(() -> new ProductSearchPort.SearchRequest("q", null, null, null, 1, 10))
            .isInstanceOf(IllegalArgumentException.class);
        String cursor = ProductSearchPort.encodeCursor(1.25d, "abc");
        ProductSearchPort.SearchCursor decoded = ProductSearchPort.decodeCursor(cursor);
        org.assertj.core.api.Assertions.assertThat(decoded.listingId()).isEqualTo("abc");
    }

    @Test
    void rejectsNulQueryPartsThatWouldCollideInFingerprint() {
        assertThatThrownBy(() -> new ProductSearchPort.SearchRequest("a\u0000b", null, null, null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductSearchPort.SearchRequest("q", "a\u0000b", null, null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalCursorScore() {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("1.25\nabc".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ProductSearchPort.decodeCursor(encoded))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
