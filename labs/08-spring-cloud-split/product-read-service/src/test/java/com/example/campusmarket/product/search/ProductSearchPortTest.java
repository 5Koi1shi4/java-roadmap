package com.example.campusmarket.product.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchPortTest {

    @Test
    void searchRequestNormalizesTextAndKeepsCursorPagingContract() {
        ProductSearchPort.SearchRequest request = new ProductSearchPort.SearchRequest(
                "  数学书  ", "  教材  ", 100L, 200L, 0, 20, "cursor");

        assertThat(request.keyword()).isEqualTo("数学书");
        assertThat(request.category()).isEqualTo("教材");
        assertThat(request.minPriceFen()).isEqualTo(100L);
        assertThat(request.maxPriceFen()).isEqualTo(200L);
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.searchAfter()).isEqualTo("cursor");
    }

    @Test
    void searchRequestRejectsInvalidRangesAndOffsetPaging() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.SearchRequest("", null, -1L, null, 0, 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.SearchRequest("", null, 200L, 100L, 0, 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.SearchRequest("", null, null, null, 1, 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.SearchRequest("", null, null, null, 0, 101));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.SearchRequest("bad\u0000input", null, null, null, 0, 20));
    }

    @Test
    void searchPageCopiesItemsAndRejectsNegativeTotals() {
        ArrayList<ProductSearchPort.SearchItem> items = new ArrayList<>();
        ProductSearchPort.SearchItem item = new ProductSearchPort.SearchItem(
                "listing-1", "数学书", "九成新", "教材", 1200, 2, "ON_SALE", 3);
        items.add(item);

        ProductSearchPort.SearchPage page = new ProductSearchPort.SearchPage(items, 1, "next");
        items.clear();

        assertThat(page.items()).containsExactly(item);
        assertThatThrownBy(() -> page.items().add(item))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.SearchPage(java.util.List.of(), -1, null));
    }

    @Test
    void productDocumentAllowsOnlyKnownStatusesAndSupportsTombstones() {
        ProductSearchPort.ProductDocument tombstone = ProductSearchPort.ProductDocument.tombstone("listing-1", 4);

        assertThat(tombstone.status()).isEqualTo("TOMBSTONE");
        assertThat(tombstone.title()).isEmpty();
        assertThat(tombstone.aggregateVersion()).isEqualTo(4);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProductSearchPort.ProductDocument(
                        "listing-1", "标题", "描述", "教材", 100, 1, "UNKNOWN", 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductSearchPort.ProductDocument.tombstone("listing-1", 0));
    }

    @Test
    void cursorRoundTripsAndRejectsNonCanonicalValues() {
        String encoded = ProductSearchPort.encodeCursor("pit", "filters", 1.5d, "listing-1");

        ProductSearchPort.SearchCursor cursor = ProductSearchPort.decodeCursor(encoded);

        assertThat(cursor.pitId()).isEqualTo("pit");
        assertThat(cursor.fingerprint()).isEqualTo("filters");
        assertThat(cursor.score()).isEqualTo(1.5d);
        assertThat(cursor.listingId()).isEqualTo("listing-1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductSearchPort.decodeCursor("not-base64"));
    }
}
