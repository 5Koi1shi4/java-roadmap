package com.example.campusmarket.order.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NoopOrderCreationHook {
    @Bean
    OrderCreationHook orderCreationHook() {
        return orderId -> { };
    }
}
