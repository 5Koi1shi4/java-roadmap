package com.example.files.unit;

import com.example.files.domain.SafeDisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeDisplayNameTest {

    @Test
    void removesPathSeparatorsAndHeaderInjectionCharacters() {
        assertThat(SafeDisplayName.from("../报告\r\nX-Test: yes.pdf").value())
            .isEqualTo("报告 X-Test_ yes.pdf");
    }

    @Test
    void rejectsBlankNames() {
        assertThatThrownBy(() -> SafeDisplayName.from(" \t\r\n "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
