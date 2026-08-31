package com.example.campusmarket.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 领域事件交换机与 durable quorum 队列。 */
@Configuration
public class RabbitTopology {
    public static final String EVENT_EXCHANGE = "campus.market.events";
    public static final String EVENT_QUEUE = "campus.market.events.order";

    @Bean
    DirectExchange campusMarketEventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    Queue campusMarketOrderEventQueue() {
        return QueueBuilder.durable(EVENT_QUEUE).quorum().build();
    }

    @Bean
    Binding campusMarketOrderEventBinding(Queue campusMarketOrderEventQueue,
                                          DirectExchange campusMarketEventExchange) {
        return BindingBuilder.bind(campusMarketOrderEventQueue).to(campusMarketEventExchange).with("ORDER_CREATED");
    }
}
