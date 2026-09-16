package com.example.campusmarket.integration;

import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Mp4FixtureTikaTest {
    @Test
    void minimalIsoBmffFixtureIsDetectedAsMp4ByProjectTika() {
        byte[] fixture = new byte[] {0,0,0,24,'f','t','y','p','m','p','4','1',0,0,2,0,'i','s','o','m',0,0,0,8,'m','o','o','v'};
        assertThat(new Tika().detect(fixture)).isEqualTo("video/mp4");
    }
}
