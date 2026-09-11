package com.example.campusmarket.unit.shared;

import com.example.campusmarket.shared.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void rejectsNegativeAndOverflow() {
        assertThatThrownBy(() -> Money.ofFen(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofFen(Long.MAX_VALUE).multiply(2))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void multipliesPositiveQuantity() {
        assertThat(Money.ofFen(125).multiply(4)).isEqualTo(Money.ofFen(500));
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> Money.ofFen(1).multiply(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofFen(1).multiply(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
