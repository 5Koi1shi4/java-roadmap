package com.example.campusmarket.integration;

import org.springframework.test.context.DynamicPropertyRegistry;

/** 旅程容器共用的禁用依赖属性；不引用任何容器生命周期类。 */
final class JourneyDependencyProperties {
    private JourneyDependencyProperties() {}

    static void registerCommonDisabledDependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> "1");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
    }
}
