package com.example.campusmarket.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import java.util.List;

/** 只有明确提供业务处理器时才注册消费者，避免生产默认吞掉事件。 */
@Configuration(proxyBeanMethods = false)
public class ReliableMessagingConfiguration {
    @Bean
    @Profile("!test")
    @ConditionalOnBean(EventBusinessHandler.class)
    ReliableEventConsumer reliableEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
        List<EventBusinessHandler> handlers) {
        if (handlers.isEmpty()) throw new IllegalStateException("未配置业务事件处理器");
        // Keep event-family ownership explicit. A warranty-only deployment must
        // reject order/payment events instead of marking their Inbox completed;
        // test fixtures can still provide handlers for the legacy families.
        EventBusinessHandler router = event -> {
            boolean warranty = event.eventType().startsWith("WARRANTY_");
            RuntimeException unsupported = null;
            for (EventBusinessHandler handler : handlers) {
                String name = handler.getClass().getName();
                boolean warrantyHandler = name.contains("WarrantyEventBusinessHandler");
                if (warranty != warrantyHandler) continue;
                try { handler.handle(event); return; }
                catch (EventBusinessHandler.UnsupportedEventException ex) { unsupported = ex; }
            }
            if (unsupported != null) throw unsupported;
            throw new EventBusinessHandler.UnsupportedEventException(event.eventType());
        };
        return new ReliableEventConsumer(codec, inbox, router);
    }
}
