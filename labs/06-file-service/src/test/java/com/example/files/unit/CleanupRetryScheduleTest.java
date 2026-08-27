package com.example.files.unit;

import com.example.files.application.cleanup.CleanupRetrySchedule;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CleanupRetryScheduleTest {
    @Test
    void usesFixedBackoffAndFailsOnFifthAttempt() {
        CleanupRetrySchedule schedule = new CleanupRetrySchedule(List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10)), 5);
        assertThat(schedule.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(schedule.delayAfter(4)).isEqualTo(Duration.ofMinutes(10));
        assertThat(schedule.shouldRetry(4)).isTrue();
        assertThat(schedule.shouldRetry(5)).isFalse();
    }
}
