package com.example.campusmarket.identity.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 身份模块指标装配，不引用 legacy 的 CampusMetrics。 */
@Configuration(proxyBeanMethods = false)
public class IdentityMetricsConfiguration {
    @Bean
    IdentityMetrics identityMetrics(MeterRegistry registry) {
        return new IdentityMetrics(registry);
    }
}
