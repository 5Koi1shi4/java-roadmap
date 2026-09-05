package com.example.campusmarket.unit.order;

import com.example.campusmarket.order.application.DeadlineScheduler;
import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadlineSchedulerTest {
    @Test
    void deadlineFailureIsReleasedWithBoundedRetryOrFailedFactInsteadOfSilentlyLooping() {
        JdbcOrderLifecycleRepository repository = mock(JdbcOrderLifecycleRepository.class);
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        UUID orderId = UUID.randomUUID();
        var claim = new JdbcOrderLifecycleRepository.DeadlineClaim(UUID.randomUUID(), orderId, "PAYMENT",
            Instant.now(), "owner", "token", Instant.now().plusSeconds(30));
        when(repository.claimBatch(any(), eq(1), any())).thenReturn(List.of(claim));
        doThrow(new IllegalStateException("inventory unavailable")).when(lifecycle).expirePayment(orderId, claim);

        new DeadlineScheduler(repository, lifecycle).runOnce(1);

        verify(repository).retryOrFailClaim(eq(claim), eq("inventory unavailable"));
    }
}
