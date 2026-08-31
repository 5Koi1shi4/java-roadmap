package com.example.campusmarket.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 事件消费者的默认空处理器；业务模块可覆盖该 Bean 执行派生事务。 */
@Configuration
public class NoopEventBusinessHandler {
    @Bean
    EventBusinessHandler eventBusinessHandler() {
        return event -> { };
    }
}
