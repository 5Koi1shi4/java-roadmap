package com.example.campusmarket.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryServerTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void serverDoesNotRegisterWithItself() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(
                "eureka.client.register-with-eureka", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty(
                "eureka.client.fetch-registry", Boolean.class)).isFalse();
        });
    }

    @Test
    void serverDeclaresExplicitUtf8ServletEncoding() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("server.servlet.encoding.charset"))
                .isEqualTo("UTF-8");
            assertThat(context.getEnvironment().getProperty(
                "server.servlet.encoding.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty(
                "server.servlet.encoding.force", Boolean.class)).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
    }
}
