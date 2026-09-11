package com.example.campusmarket.unit.observability;

import com.example.campusmarket.observability.AuditRecorder;
import com.example.campusmarket.observability.SafeAuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AuditRecorderTest {
    @Test
    void databaseOwnsAuditEventFactTimeWithMicrosecondCurrentTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String[] sql = new String[1];
        doAnswer(invocation -> {
            sql[0] = invocation.getArgument(0, String.class);
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));

        new AuditRecorder(jdbc).record(SafeAuditEvent.success(
            UUID.randomUUID(), "REVIEW_CREATED", "TRADE_ORDER", UUID.randomUUID(), Map.of()));

        assertThat(sql[0]).contains("CURRENT_TIMESTAMP(6)");
    }
}
