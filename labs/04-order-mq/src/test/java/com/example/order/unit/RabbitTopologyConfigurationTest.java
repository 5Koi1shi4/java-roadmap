package com.example.order.unit;

import com.example.order.infrastructure.mq.FailureClassifier;
import com.example.order.infrastructure.mq.OrderTimeoutConsumer;
import com.example.order.infrastructure.mq.NonRetryableMessageException;
import com.example.order.infrastructure.mq.RabbitTopologyConfiguration;
import com.example.order.application.OrderService;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitTopologyConfigurationTest {
    @Test
    void timeoutConsumerListensToCancelQueueAfterTtlDeadLettering() throws Exception {
        Method method = OrderTimeoutConsumer.class.getDeclaredMethod("consume", Message.class);

        assertThat(method.getAnnotation(RabbitListener.class).queues())
                .containsExactly(RabbitTopologyConfiguration.CANCEL_QUEUE);
    }

    @Test
    void applicationContextCreatesMessagingBeansWithOneConsumerConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(RabbitTopologyConfiguration.class, TestBeans.class)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FailureClassifier.class);
                    assertThat(context).hasSingleBean(OrderTimeoutConsumer.class);
                });
    }

    @Test
    void listenerDoesNotRequeueAndManualRecoveryRequiresPublisherConfirm() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        RabbitTopologyConfiguration configuration = new RabbitTopologyConfiguration();
        SimpleRabbitListenerContainerFactory factory = configuration.rabbitListenerContainerFactory(
                mock(ConnectionFactory.class), configuration.timeoutRetryInterceptor(
                        configuration.timeoutMessageRecoverer(template, new FailureClassifier(), repository,
                                new ObjectMapper())));
        assertThat(factory).isNotNull();

        when(template.invoke(any())).thenReturn(false);
        Message message = new Message(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(),
                new MessageProperties());
        MessageRecoverer recoverer = configuration.timeoutMessageRecoverer(
                template, new FailureClassifier(), repository, new ObjectMapper());

        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException("bad json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("publisher confirm");
        verify(template).invoke(any());
        verify(repository, never()).recordConsumptionFailure(any(), any(), any(), any());
    }

    @Test
    void durableFailureIsPersistedBeforeManualPublishAttempt() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        JdbcOrderRepository repository = mock(JdbcOrderRepository.class);
        when(repository.insertManualFailure(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(42L);
        when(template.invoke(any())).thenReturn(false);
        Message message = new Message(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(),
                new MessageProperties());
        MessageRecoverer recoverer = new RabbitTopologyConfiguration().timeoutMessageRecoverer(
                template, new FailureClassifier(), repository, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> recoverer.recover(message, new RuntimeException("manual down")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        org.mockito.InOrder order = inOrder(repository, template);
        order.verify(repository).insertManualFailure(any(), any(), any(), any(), any(Integer.class), any());
        order.verify(template).invoke(any());
        verify(repository, never()).markManualDelivered(any(Long.class), any());
    }

    @Test
    void manualRecoveryWritesActualAttemptCountInsteadOfInboundHeader() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitOperations operations = mock(RabbitOperations.class);
        AtomicReference<Message> sent = new AtomicReference<>();
        doAnswer(invocation -> {
            sent.set(invocation.getArgument(2));
            return null;
        }).when(operations).send(any(String.class), any(String.class), any(Message.class));
        when(operations.waitForConfirms(10_000L)).thenReturn(true);
        when(template.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInRabbit(operations);
        });

        Message incoming = new Message(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(),
                new MessageProperties());
        incoming.getMessageProperties().setHeader("x-retry-count", 99);
        MessageRecoverer recoverer = new RabbitTopologyConfiguration().timeoutMessageRecoverer(
                template, new FailureClassifier(), mock(JdbcOrderRepository.class), new ObjectMapper());
        RetryContextSupport context = new RetryContextSupport(null);
        context.registerThrowable(new RuntimeException("attempt 1"));
        context.registerThrowable(new RuntimeException("attempt 2"));
        RetrySynchronizationManager.register(context);
        try {
            recoverer.recover(incoming, new com.example.order.infrastructure.mq.RetryableMessageException("down"));
        } finally {
            RetrySynchronizationManager.clear();
        }

        assertThat(sent.get().getMessageProperties().getHeaders().get("x-retry-count")).isEqualTo(3);
    }

    @Test
    void exhaustedInboundHeaderIsRetainedAtLeastThree() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitOperations operations = mock(RabbitOperations.class);
        AtomicReference<Message> sent = new AtomicReference<>();
        doAnswer(invocation -> {
            sent.set(invocation.getArgument(2));
            return null;
        }).when(operations).send(any(String.class), any(String.class), any(Message.class));
        when(operations.waitForConfirms(10_000L)).thenReturn(true);
        when(template.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInRabbit(operations);
        });
        Message incoming = new Message(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(),
                new MessageProperties());
        incoming.getMessageProperties().setHeader("x-retry-count", 3);
        incoming.getMessageProperties().setHeader("x-retry-exhausted", true);

        new RabbitTopologyConfiguration().timeoutMessageRecoverer(
                template, new FailureClassifier(), mock(JdbcOrderRepository.class), new ObjectMapper())
                .recover(incoming, new NonRetryableMessageException("bad"));

        assertThat(sent.get().getMessageProperties().getHeaders().get("x-retry-count")).isEqualTo(3);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OrderTimeoutConsumer.class)
    static class TestBeans {
        @Bean
        JdbcOrderRepository repository() {
            return mock(JdbcOrderRepository.class);
        }

        @Bean
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
