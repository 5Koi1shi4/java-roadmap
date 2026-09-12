package com.example.campusmarket.identity.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpMailPropertiesTest {
    @Test
    void rejectsMissingHostOrSenderAddress() {
        assertThatThrownBy(() -> new SmtpMailProperties(null, 587, null, null, "noreply@example.edu.cn"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmtpMailProperties("smtp.example.edu.cn", 587, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
