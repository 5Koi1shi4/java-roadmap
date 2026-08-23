package com.example.order.unit;

import com.example.order.infrastructure.mq.OrderEventPublisher;
import com.example.order.infrastructure.mq.OutboxDispatcher;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxDispatcherSpringWiringTest {
    @Test
    void selectsTheProductionConstructorWhenRegisteredAsSpringBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcOrderRepository.class, () -> mock(JdbcOrderRepository.class));
            context.registerBean(OrderEventPublisher.class, () -> mock(OrderEventPublisher.class));
            context.register(OutboxDispatcher.class);

            context.refresh();

            assertThat(context.getBean(OutboxDispatcher.class)).isNotNull();
        }
    }
}
