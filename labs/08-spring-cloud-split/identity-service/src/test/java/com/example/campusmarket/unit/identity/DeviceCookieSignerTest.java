package com.example.campusmarket.unit.identity;

import com.example.campusmarket.identity.infrastructure.DeviceCookieSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCookieSignerTest {
    private final DeviceCookieSigner signer = new DeviceCookieSigner(
        "local-only-verification-secret-change-me-32-bytes");

    @Test
    void signsRandomPayloadAndRejectsTampering() {
        String cookie = signer.issue();

        assertThat(cookie).contains(".");
        assertThat(signer.verify(cookie)).isTrue();
        String signature = cookie.substring(cookie.indexOf('.') + 1);
        char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
        String forged = cookie.substring(0, cookie.indexOf('.') + 1) + replacement + signature.substring(1);
        assertThat(signer.verify(forged)).isFalse();
    }

    @Test
    void forgedValuesDoNotBecomeAttackerControlledRateLimitDimensions() {
        String first = signer.fallback("127.0.0.1");
        String second = signer.fallback("127.0.0.1");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(signer.fallback("127.0.0.2"));
    }
}
