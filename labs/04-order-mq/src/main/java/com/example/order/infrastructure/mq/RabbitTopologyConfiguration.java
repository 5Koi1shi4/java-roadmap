package com.example.order.infrastructure.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/** Rabbit topology and listener retry policy for timeout events. */
@Configuration
public class RabbitTopologyConfiguration {
    public static final String TIMEOUT_EXCHANGE = "order.timeout.exchange";
    public static final String CANCEL_EXCHANGE = "order.cancel.exchange";
    public static final String TIMEOUT_QUEUE_10S = "order.timeout.10s.queue";
    public static final String TIMEOUT_QUEUE_1M = "order.timeout.1m.queue";
    public static final String TIMEOUT_QUEUE_5M = "order.timeout.5m.queue";
    public static final String CANCEL_QUEUE = "order.cancel.queue";
    public static final String MANUAL_QUEUE = "order.manual.queue";
    public static final String TIMEOUT_ROUTING_KEY = "order.timeout.1m";
    public static final String CANCEL_ROUTING_KEY = "order.cancel";

    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(TIMEOUT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderCancelExchange() {
        return new DirectExchange(CANCEL_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderTimeout10sQueue() {
        return timeoutQueue(TIMEOUT_QUEUE_10S, 10_000L);
    }

    @Bean
    public Queue orderTimeout1mQueue() {
        return timeoutQueue(TIMEOUT_QUEUE_1M, 60_000L);
    }

    @Bean
    public Queue orderTimeout5mQueue() {
        return timeoutQueue(TIMEOUT_QUEUE_5M, 300_000L);
    }

    private Queue timeoutQueue(String name, long ttl) {
        return new Queue(name, true, false, false, Map.of(
                "x-message-ttl", ttl,
                "x-dead-letter-exchange", CANCEL_EXCHANGE,
                "x-dead-letter-routing-key", CANCEL_ROUTING_KEY));
    }

    @Bean
    public Queue orderCancelQueue() {
        return new Queue(CANCEL_QUEUE, true);
    }

    @Bean
    public Queue orderManualQueue() {
        return new Queue(MANUAL_QUEUE, true);
    }

    @Bean
    public Binding timeout1mBinding(@Qualifier("orderTimeout1mQueue") Queue orderTimeout1mQueue,
                                    @Qualifier("orderTimeoutExchange") DirectExchange orderTimeoutExchange) {
        return BindingBuilder.bind(orderTimeout1mQueue).to(orderTimeoutExchange).with(TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding timeout10sBinding(@Qualifier("orderTimeout10sQueue") Queue queue,
                                     @Qualifier("orderTimeoutExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("order.timeout.10s");
    }

    @Bean
    public Binding timeout5mBinding(@Qualifier("orderTimeout5mQueue") Queue queue,
                                   @Qualifier("orderTimeoutExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("order.timeout.5m");
    }

    @Bean
    public Binding cancelBinding(@Qualifier("orderCancelQueue") Queue orderCancelQueue,
                                 @Qualifier("orderCancelExchange") DirectExchange orderCancelExchange) {
        return BindingBuilder.bind(orderCancelQueue).to(orderCancelExchange).with(CANCEL_ROUTING_KEY);
    }

    @Bean
    public RetryOperationsInterceptor timeoutRetryInterceptor(MessageRecoverer timeoutMessageRecoverer) {
        SimpleRetryPolicy policy = new SimpleRetryPolicy(
                3, Map.of(RetryableMessageException.class, true), true);
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(policy)
                .backOffOptions(1_000L, 1.0, 1_000L)
                .recoverer(timeoutMessageRecoverer)
                .build();
    }

    @Bean
    public MessageRecoverer timeoutMessageRecoverer(RabbitTemplate rabbitTemplate,
                                                     FailureClassifier classifier) {
        return (message, failure) -> {
            MessageProperties properties = new MessageProperties();
            properties.setHeaders(message.getMessageProperties().getHeaders());
            properties.setContentType(message.getMessageProperties().getContentType());
            properties.setHeader("failure-category", classifier.classify(failure).name());
            properties.setHeader("failure-message", messageOf(failure));
            Object retryCount = message.getMessageProperties().getHeaders().get("x-retry-count");
            properties.setHeader("x-retry-count", retryCount == null ? 3 : retryCount);
            rabbitTemplate.send("", MANUAL_QUEUE,
                    new Message(message.getBody(), properties));
        };
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, RetryOperationsInterceptor timeoutRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAdviceChain(timeoutRetryInterceptor);
        return factory;
    }

    private static String messageOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
