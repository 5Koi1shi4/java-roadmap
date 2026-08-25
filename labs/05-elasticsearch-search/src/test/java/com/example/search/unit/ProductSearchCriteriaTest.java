package com.example.search.unit;

import com.example.search.application.search.ProductSearchCriteria;
import com.example.search.application.search.ProductSort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchCriteriaTest {
    @Test
    void normalizesBlankKeywordAndAcceptsThePublicWindow() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of("  ", "BOOK",
                new BigDecimal("1.00"), new BigDecimal("9.99"), 1, 50, "priceAsc");

        assertThat(criteria.keyword()).isEmpty();
        assertThat(criteria.categoryCode()).contains("BOOK");
        assertThat(criteria.sort()).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(criteria.offset()).isEqualTo(50);
    }

    @Test
    void rejectsOverlongUnicodeKeywordAndInvalidPagingOrPrices() {
        String overlong = "界".repeat(101);
        assertThatThrownBy(() -> ProductSearchCriteria.of(overlong, null, null, null, 0, 10, "relevance"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, null, null, -1, 10, "relevance"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, null, null, 1000, 11, "relevance"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, new BigDecimal("-0.01"), null, 0, 10, "relevance"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, new BigDecimal("9"), new BigDecimal("1"), 0, 10, "relevance"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesExactlyTheFourSupportedSorts() {
        assertThat(ProductSort.parse("relevance")).isEqualTo(ProductSort.RELEVANCE);
        assertThat(ProductSort.parse("priceAsc")).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(ProductSort.parse("priceDesc")).isEqualTo(ProductSort.PRICE_DESC);
        assertThat(ProductSort.parse("newest")).isEqualTo(ProductSort.NEWEST);
        assertThatThrownBy(() -> ProductSort.parse("PRICE_ASC"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
