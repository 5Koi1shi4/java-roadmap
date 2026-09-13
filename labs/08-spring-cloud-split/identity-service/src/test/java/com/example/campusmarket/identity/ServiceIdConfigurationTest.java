package com.example.campusmarket.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceIdConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void identityServiceUsesFixedServiceId() {
        contexts.run(context -> assertThat(context.getEnvironment()
            .getProperty("spring.application.name")).isEqualTo("identity-service"));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
    }
}
