package com.example.campusmarket.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.example.campusmarket.observability.CampusMetrics;
import java.util.List;

/** 只有明确提供业务处理器时才注册消费者，避免生产默认吞掉事件。 */
@Configuration(proxyBeanMethods = false)
public class ReliableMessagingConfiguration {
    @Bean
    @Profile("!test")
    @ConditionalOnBean(EventBusinessHandler.class)
    ReliableEventConsumer reliableEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
        List<EventBusinessHandler> handlers, CampusMetrics metrics) {
        if (handlers.isEmpty()) throw new IllegalStateException("未配置业务事件处理器");
        // Keep event-family ownership explicit. A warranty-only deployment must
        // reject order/payment events instead of marking their Inbox completed;
        // test fixtures can still provide handlers for the legacy families.
        EventBusinessHandler router = event -> {
            RuntimeException unsupported = null;
            for (EventBusinessHandler handler : handlers) {
                if (!handler.supports(event.eventType())) continue;
                try { handler.handle(event); return; }
                catch (EventBusinessHandler.UnsupportedEventException ex) { unsupported = ex; }
            }
            if (unsupported != null) throw unsupported;
            throw new EventBusinessHandler.UnsupportedEventException(event.eventType());
        };
        return new ReliableEventConsumer(codec, inbox, router, metrics);
    }

    @Bean
    @Profile("!test")
    @ConditionalOnBean(EventBusinessHandler.class)
    WarrantyEventConsumer warrantyEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                                List<EventBusinessHandler> handlers, CampusMetrics metrics) {
        EventBusinessHandler handler = handlers.stream()
            .filter(h -> h.supports("WARRANTY_REFUND_REQUESTED"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("未配置质保事件处理器"));
        return new WarrantyEventConsumer(codec, inbox, handler, metrics);
    }
}
