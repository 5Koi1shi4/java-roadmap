package com.example.search.integration;

import com.example.search.config.SearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPropertiesBindingIT {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void bindsNonDefaultValuesThroughTheRealSpringBinder() {
        runner.withPropertyValues("search.batch-size=7", "search.lease-duration=12s",
                        "search.request-timeout=2s", "search.dispatch-delay=3s",
                        "search.maintenance.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SearchProperties properties = context.getBean(SearchProperties.class);
                    assertThat(properties.batchSize()).isEqualTo(7);
                    assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.dispatchDelay()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.maintenance().enabled()).isTrue();
                });
    }

    @Test
    void rejectsInvalidValuesDuringRealSpringBinding() {
        runner.withPropertyValues("search.batch-size=51", "search.lease-duration=30s",
                        "search.request-timeout=10s", "search.dispatch-delay=1s",
                        "search.maintenance.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("batch size must be between 1 and 50");
                });
    }

    @Test
    void bindsSafeDefaultsWhenSearchConfigurationIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SearchProperties properties = context.getBean(SearchProperties.class);
            assertThat(properties.batchSize()).isEqualTo(50);
            assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.dispatchDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.maintenance().enabled()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SearchProperties.class)
    static class BindingConfiguration { }
}
