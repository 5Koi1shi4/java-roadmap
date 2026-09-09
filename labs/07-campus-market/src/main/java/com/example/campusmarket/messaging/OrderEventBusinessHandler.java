package com.example.campusmarket.messaging;

import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.shared.DomainEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 订单事件的生产业务处理器。恢复演练和 Rabbit 消费都经过同一个订单用例，
 * 不在测试中用 lambda 直接改写 trade_order 或补发 Outbox。
 */
@Component
@Profile("!test")
public final class OrderEventBusinessHandler implements EventBusinessHandler {
    private static final Set<String> OWNED = Set.of("ORDER_PAID");
    private final OrderLifecycleService lifecycle;

    public OrderEventBusinessHandler(OrderLifecycleService lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "订单生命周期用例不能为空");
    }

    @Override
    public boolean supports(String eventType) {
        return OWNED.contains(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        Objects.requireNonNull(event, "订单事件不能为空");
        if (!supports(event.eventType())) throw new UnsupportedEventException(event.eventType());
        UUID orderId;
        try {
            orderId = UUID.fromString(event.aggregateId());
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalArgumentException("订单事件聚合 ID 无效", invalidId);
        }
        if (!lifecycle.applyPaymentSucceeded(orderId)) {
            throw new IllegalStateException("订单支付事件未能收敛");
        }
    }
}
