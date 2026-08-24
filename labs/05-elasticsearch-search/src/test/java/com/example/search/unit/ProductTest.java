package com.example.search.unit;

import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {
    @Test
    void rejectsInvalidPriceAndDeletedCreationState() {
        assertThatThrownBy(() -> new ProductDetails("书", null, "教材", "BOOK", "图书",
                new BigDecimal("-0.01"), ProductStatus.ON_SALE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductDetails("书", null, "教材", "BOOK", "图书",
                BigDecimal.TEN, ProductStatus.DELETED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
