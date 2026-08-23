package com.example.order.unit;

import com.example.order.application.CancellationResult;
import com.example.order.application.CreateOrderCommand;
import com.example.order.application.InsufficientStockException;
import com.example.order.application.InvalidOrderEventException;
import com.example.order.application.OrderService;
import com.example.order.application.OrderTimeoutEvent;
import com.example.order.application.PayOrderCommand;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock
    JdbcOrderRepository repository;

    @Test
    void createsPendingOrderAndNewTimeoutOutboxEventInOneUseCase() {
        when(repository.decrementStockIfAvailable(1L, 2)).thenReturn(1);
        when(repository.insertPendingOrder(1L, 2)).thenReturn(42L);
        OrderService service = new OrderService(repository);

        long orderId = service.createOrder(new CreateOrderCommand(1, 2));

        assertThat(orderId).isEqualTo(42L);
        InOrder order = inOrder(repository);
        order.verify(repository).decrementStockIfAvailable(1L, 2);
        order.verify(repository).insertPendingOrder(1L, 2);
        order.verify(repository).insertTimeoutOutbox(any(OrderTimeoutEvent.class));
    }

    @Test
    void rejectsInsufficientStockBeforeCreatingOrderOrOutbox() {
        when(repository.decrementStockIfAvailable(1L, 2)).thenReturn(0);
        OrderService service = new OrderService(repository);

        assertThatThrownBy(() -> service.createOrder(new CreateOrderCommand(1, 2)))
                .isInstanceOf(InsufficientStockException.class);

        verify(repository, never()).insertPendingOrder(any(Long.class), any(Integer.class));
        verify(repository, never()).insertTimeoutOutbox(any(OrderTimeoutEvent.class));
    }

    @Test
    void rejectsMalformedTimeoutEventBeforeChangingOrder() {
        assertThatThrownBy(() -> new OrderTimeoutEvent(UUID.randomUUID(), "OTHER", 1L, Instant.now(), 1))
                .isInstanceOf(InvalidOrderEventException.class);
    }

    @Test
    void rejectsNullTimeoutEventBeforeChangingOrder() {
        OrderService service = new OrderService(repository);

        assertThatThrownBy(() -> service.cancelExpired(null))
                .isInstanceOf(InvalidOrderEventException.class);

        verify(repository, never()).cancelIfPending(any(Long.class));
        verify(repository, never()).releaseStock(any(Long.class), any(Integer.class));
    }

    @Test
    void mapsConditionalCancellationAndReleasesStockOnlyWhenOrderWasPending() {
        OrderTimeoutEvent event = validEvent(9L);
        when(repository.cancelIfPending(9L)).thenReturn(1);
        when(repository.orderProductAndQuantity(9L)).thenReturn(new JdbcOrderRepository.OrderProductQuantity(1L, 2));
        OrderService service = new OrderService(repository);

        assertThat(service.cancelExpired(event)).isEqualTo(CancellationResult.CANCELLED);
        verify(repository).releaseStock(1L, 2);
    }

    @Test
    void mapsAlreadyPaidAndAlreadyCancelledWithoutReleasingStock() {
        OrderService service = new OrderService(repository);
        OrderTimeoutEvent paid = validEvent(9L);
        OrderTimeoutEvent cancelled = validEvent(10L);
        when(repository.cancelIfPending(9L)).thenReturn(0);
        when(repository.cancelIfPending(10L)).thenReturn(0);
        when(repository.orderStatus(9L)).thenReturn("PAID");
        when(repository.orderStatus(10L)).thenReturn("CANCELLED");

        assertThat(service.cancelExpired(paid)).isEqualTo(CancellationResult.ALREADY_PAID);
        assertThat(service.cancelExpired(cancelled)).isEqualTo(CancellationResult.ALREADY_CANCELLED);
        verify(repository, never()).releaseStock(any(Long.class), any(Integer.class));
    }

    @Test
    void paysOnlyPendingOrder() {
        when(repository.markPaidIfPending(42L)).thenReturn(1);
        OrderService service = new OrderService(repository);

        assertThatCode(() -> service.pay(new PayOrderCommand(42L))).doesNotThrowAnyException();
        verify(repository).markPaidIfPending(42L);
    }

    @Test
    void createOrderDefinesTransactionalBoundary() throws Exception {
        assertThat(OrderService.class.getDeclaredMethod("createOrder", CreateOrderCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private OrderTimeoutEvent validEvent(long orderId) {
        return new OrderTimeoutEvent(UUID.randomUUID(), "ORDER_TIMEOUT", orderId, Instant.now(), 1);
    }
}
