package com.example.files.integration;

import com.example.files.config.FileServiceProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 维护配置的安全边界回归。 */
class CleanupMaintenanceIT {
    @Test void cleanupScheduleIsPositiveAndTyped() {
        FileServiceProperties.Cleanup cleanup = new FileServiceProperties.Cleanup(50, Duration.ofSeconds(30),
            List.of(Duration.ofSeconds(5)), 5, Duration.ofHours(24), Duration.ofMinutes(1));
        assertThat(cleanup.schedule()).isEqualTo(Duration.ofMinutes(1));
    }
}
