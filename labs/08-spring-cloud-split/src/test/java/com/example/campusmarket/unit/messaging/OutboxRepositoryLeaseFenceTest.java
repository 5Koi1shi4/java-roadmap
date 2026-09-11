package com.example.campusmarket.unit.messaging;

import com.example.campusmarket.messaging.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class OutboxRepositoryLeaseFenceTest {
    @Test
    void everyTerminalMutationRequiresAnUnexpiredLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        OutboxRepository repository = new OutboxRepository(jdbc);
        UUID eventId = UUID.randomUUID();

        repository.complete(eventId, "owner", "token");
        repository.releaseForRetry(eventId, "owner", "token", Duration.ofSeconds(1));
        repository.fail(eventId, "owner", "token", "PERMANENT");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).filteredOn(statement -> statement.startsWith("UPDATE")).allSatisfy(statement ->
            assertThat(statement).contains("lease_until > CURRENT_TIMESTAMP(6)"));
    }

    @Test
    void failureOnlyWritesManualCopyAfterOutboxTerminalUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        OutboxRepository repository = new OutboxRepository(jdbc);

        repository.fail(UUID.randomUUID(), "owner", "token", "PERMANENT");

        InOrder order = org.mockito.Mockito.inOrder(jdbc);
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("UPDATE integration_outbox"), any(Object[].class));
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO manual_failure"), any(Object[].class));
    }

    @Test
    void failureDoesNotWriteManualCopyWhenOutboxFenceRejectsUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        OutboxRepository repository = new OutboxRepository(jdbc);

        assertThat(repository.fail(UUID.randomUUID(), "owner", "token", "PERMANENT")).isZero();

        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("UPDATE integration_outbox"), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }
}
