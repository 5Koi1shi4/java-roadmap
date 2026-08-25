package com.example.search.unit;

import com.example.search.config.SearchProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchPropertiesTest {
    @Test
    void acceptsSafeDefaultValues() {
        SearchProperties properties = new SearchProperties(50, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(1), new SearchProperties.Maintenance(false));

        assertThat(properties.batchSize()).isEqualTo(50);
        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.dispatchDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.maintenance().enabled()).isFalse();
    }

    @Test
    void rejectsLeaseThatCannotFenceTheRequestTimeout() {
        assertThatThrownBy(() -> new SearchProperties(50, Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                new SearchProperties.Maintenance(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    @Test
    void rejectsBatchOutsideDispatcherLimit() {
        assertThatThrownBy(() -> new SearchProperties(51, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                new SearchProperties.Maintenance(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch");
    }
}
