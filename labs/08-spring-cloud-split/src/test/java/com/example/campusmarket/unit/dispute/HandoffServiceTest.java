package com.example.campusmarket.unit.dispute;

import com.example.campusmarket.dispute.application.HandoffService;
import com.example.campusmarket.order.application.IdempotentCommandService;
import com.example.campusmarket.order.application.OrderLifecycleService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HandoffServiceTest {
    @Test
    void parsesUnifiedConflictErrorAsUnsuccessfulForHttpStatusMapping() {
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        IdempotentCommandService commands = mock(IdempotentCommandService.class);
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        when(lifecycle.handoff(orderId, sellerId, "")).thenReturn(false);
        when(commands.executeLifecycle(any(), any(), any(), any(), any(), any(), any(Supplier.class)))
            .thenAnswer(invocation -> invocation.getArgument(6, Supplier.class).get());

        HandoffService.Result result = new HandoffService(lifecycle, commands)
            .handoff(orderId, sellerId, "handoff-key", "");

        assertThat(result.success()).isFalse();
        assertThat(new String(result.responseUtf8(), StandardCharsets.UTF_8))
            .contains("\"code\":\"CONFLICT\"")
            .doesNotContain("\"error\"");
    }
}
