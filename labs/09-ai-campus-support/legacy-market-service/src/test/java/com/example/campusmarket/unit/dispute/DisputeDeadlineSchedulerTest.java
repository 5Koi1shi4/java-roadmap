package com.example.campusmarket.unit.dispute;

import com.example.campusmarket.dispute.application.DisputeDeadlineScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeDeadlineSchedulerTest {
    @Test
    void amountOverflowIsRejectedInsteadOfTurningIntoMaxLong() throws Exception {
        Method method = DisputeDeadlineScheduler.class.getDeclaredMethod("amount", long.class, int.class);
        method.setAccessible(true);
        assertThatThrownBy(() -> method.invoke(null, Long.MAX_VALUE, 2))
            .isInstanceOf(InvocationTargetException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
