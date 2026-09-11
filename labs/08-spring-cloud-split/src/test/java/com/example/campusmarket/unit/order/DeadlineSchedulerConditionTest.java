package com.example.campusmarket.unit.order;

import com.example.campusmarket.order.application.DeadlineScheduler;
import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeadlineSchedulerConditionTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withUserConfiguration(SchedulerConfiguration.class)
        .withBean(JdbcOrderLifecycleRepository.class, () -> mock(JdbcOrderLifecycleRepository.class))
        .withBean(OrderLifecycleService.class, () -> mock(OrderLifecycleService.class));

    @Test
    void evidenceTestConfigurationCanDisableDeadlineSchedulerBeforeDatabaseShutdown() {
        contexts.withPropertyValues("campus.market.order.deadline.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(DeadlineScheduler.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DeadlineScheduler.class)
    static class SchedulerConfiguration { }
}
