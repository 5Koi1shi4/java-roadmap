package com.example.order.unit;

import com.example.order.infrastructure.mq.OrderEventPublisher;
import com.example.order.infrastructure.mq.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderEventPublisherTest {
    @Test
    void usesOneMinuteBucketByDefault() {
        assertThat(publishedRoutingKey(null))
                .isEqualTo("order.timeout.1m");
    }

    @Test
    void allowsIntegrationTestToUseTenSecondBucket() {
        assertThat(publishedRoutingKey("order.timeout.10s"))
                .isEqualTo("order.timeout.10s");
    }

    @Test
    void springPropertyOverridesPublisherRoutingKey() {
        AtomicReference<String> routingKey = new AtomicReference<>();
        RabbitTemplate template = templateFor(routingKey);

        new ApplicationContextRunner()
                .withBean(RabbitTemplate.class, () -> template)
                .withUserConfiguration(PublisherContext.class)
                .withPropertyValues("order.timeout.routing-key=order.timeout.10s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(OrderEventPublisher.class).publish(event());
                    assertThat(routingKey).hasValue("order.timeout.10s");
                });
    }

    private String publishedRoutingKey(String configuredRoutingKey) {
        AtomicReference<String> routingKey = new AtomicReference<>();
        RabbitTemplate template = templateFor(routingKey);
        OrderEventPublisher publisher = configuredRoutingKey == null
                ? new OrderEventPublisher(template)
                : new OrderEventPublisher(template, configuredRoutingKey);
        publisher.publish(event());
        return routingKey.get();
    }

    private RabbitTemplate templateFor(AtomicReference<String> routingKey) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitOperations operations = mock(RabbitOperations.class);
        doAnswer(invocation -> {
            routingKey.set(invocation.getArgument(1));
            return null;
        }).when(operations).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
        when(operations.waitForConfirms(anyLong())).thenReturn(true);
        when(template.invoke(any())).thenAnswer(invocation ->
                ((RabbitOperations.OperationsCallback<Boolean>) invocation.getArgument(0)).doInRabbit(operations));
        return template;
    }

    private OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "ORDER", 1L,
                "ORDER_TIMEOUT", "{}", Instant.now());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OrderEventPublisher.class)
    static class PublisherContext {
    }
}
