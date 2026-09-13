package com.example.campusmarket.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceServerConfigurationTest {
    @Test
    void rejectsNonContractAudienceBeforeBuildingDecoder() {
        assertThatThrownBy(() -> new ResourceServerConfiguration().jwtDecoder(
            "http://127.0.0.1:1/jwks", "http://gateway.test", "wrong-audience"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("campus-market-api");
    }
}
