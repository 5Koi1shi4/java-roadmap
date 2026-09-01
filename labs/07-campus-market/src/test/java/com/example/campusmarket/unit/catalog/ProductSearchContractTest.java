package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchSchema;
import org.junit.jupiter.api.Test;

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
}
