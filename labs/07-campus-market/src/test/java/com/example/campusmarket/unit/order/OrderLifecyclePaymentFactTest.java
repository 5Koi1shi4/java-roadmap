package com.example.campusmarket.unit.order;

import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderLifecyclePaymentFactTest {
    @Test
    void paidEventCannotAdvanceOrderWithoutMatchingSuccessfulPaymentAggregate() {
        JdbcOrderLifecycleRepository repository = mock(JdbcOrderLifecycleRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(manager).commit(any(TransactionStatus.class));
        UUID orderId = UUID.randomUUID();
        when(repository.lock(orderId)).thenReturn(new JdbcOrderLifecycleRepository.OrderRow(orderId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 100, OrderStatus.PENDING_PAYMENT, 1,
            Instant.now().plusSeconds(60), null, null, null, null, null, null));
        when(repository.hasMatchingSuccessfulPayment(orderId)).thenReturn(false);
        OrderLifecycleService service = new OrderLifecycleService(repository, mock(InventoryPort.class), manager);

        assertThat(service.applyPaymentSucceeded(orderId)).isFalse();
        verify(repository, never()).transition(any(), any(), any(), anyLong(), any(), any(), anyBoolean(), any(), any(), any(), anyString());
    }
}
