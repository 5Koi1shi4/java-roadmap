package com.example.campusmarket.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 只有明确提供业务处理器时才注册消费者，避免生产默认吞掉事件。 */
@Configuration(proxyBeanMethods = false)
public class ReliableMessagingConfiguration {
    @Bean
    @Profile("!test")
    @ConditionalOnBean(EventBusinessHandler.class)
    ReliableEventConsumer reliableEventConsumer(EventEnvelopeCodec codec, InboxRepository inbox,
                                                 EventBusinessHandler businessHandler,
                                                 ManualFailurePublisher manualPublisher) {
        return new ReliableEventConsumer(codec, inbox, businessHandler, manualPublisher);
    }
}
