package com.example.campusmarket.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ReactorResourceFactory;

/** 为同一 JVM 内运行的每个测试应用隔离 Reactor Netty 生命周期。 */
@TestConfiguration(proxyBeanMethods = false)
class IsolatedReactorNettyConfiguration {

    @Bean
    ReactorResourceFactory reactorResourceFactory() {
        ReactorResourceFactory factory = new ReactorResourceFactory();
        factory.setUseGlobalResources(false);
        return factory;
    }
}
