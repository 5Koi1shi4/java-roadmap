package com.example.order.application;

import com.example.order.domain.IllegalOrderTransitionException;
import com.example.order.domain.OrderStatus;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderService {
    private final JdbcOrderRepository repository;

    public OrderService(JdbcOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public long createOrder(CreateOrderCommand command) {
        int changed = repository.decrementStockIfAvailable(command.productId(), command.quantity());
        if (changed == 0) {
            throw new InsufficientStockException(command.productId(), command.quantity());
        }
        long orderId = repository.insertPendingOrder(command.productId(), command.quantity());
        repository.insertTimeoutOutbox(new OrderTimeoutEvent(
                UUID.randomUUID(), "ORDER_TIMEOUT", orderId, Instant.now(), 1));
        return orderId;
    }

    @Transactional
    public void pay(PayOrderCommand command) {
        if (repository.markPaidIfPending(command.orderId()) == 1) {
            return;
        }
        OrderStatus current = readStatus(command.orderId());
        throw new IllegalOrderTransitionException(current, OrderStatus.PAID);
    }

    @Transactional
    public CancellationResult cancelExpired(OrderTimeoutEvent event) {
        validateEvent(event);
        if (repository.cancelIfPending(event.orderId()) == 1) {
            JdbcOrderRepository.OrderProductQuantity order = repository.orderProductAndQuantity(event.orderId());
            if (order == null) {
                throw new IllegalStateException("Cancelled order disappeared: " + event.orderId());
            }
            repository.releaseStock(order.productId(), order.quantity());
            return CancellationResult.CANCELLED;
        }
        OrderStatus current = readStatus(event.orderId());
        return switch (current) {
            case PAID -> CancellationResult.ALREADY_PAID;
            case CANCELLED -> CancellationResult.ALREADY_CANCELLED;
            case PENDING_PAYMENT -> throw new IllegalStateException("Order cancellation was not applied");
        };
    }

    private void validateEvent(OrderTimeoutEvent event) {
        if (event == null) {
            throw new InvalidOrderEventException("event must not be null");
        }
        if (event.eventId() == null || !"ORDER_TIMEOUT".equals(event.eventType())
                || event.orderId() <= 0 || event.occurredAt() == null || event.version() != 1) {
            throw new InvalidOrderEventException("Malformed ORDER_TIMEOUT event");
        }
    }

    private OrderStatus readStatus(long orderId) {
        String status = repository.orderStatus(orderId);
        if (status == null) {
            throw new IllegalStateException("Order not found: " + orderId);
        }
        return OrderStatus.valueOf(status);
    }
}
