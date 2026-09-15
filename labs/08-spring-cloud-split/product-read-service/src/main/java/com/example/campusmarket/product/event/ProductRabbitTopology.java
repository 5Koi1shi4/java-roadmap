package com.example.campusmarket.product.event;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;

/** 读服务自行声明与发布端相同的持久 Rabbit 协议拓扑。 */
@Configuration(proxyBeanMethods = false)
public class ProductRabbitTopology {
    public static final String PRODUCT_EXCHANGE = "campus.product.snapshot";
    public static final String MANUAL_EXCHANGE = "campus.market.manual";
    public static final String MANUAL_QUEUE = "campus.market.manual.failure";

    private static final List<String> EVENT_TYPES = List.of(
        "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED",
        "LISTING_OFF_SALE", "LISTING_SOLD_OUT", "INVENTORY_CHANGED");

    @Bean
    DirectExchange productSnapshotExchange() {
        return new DirectExchange(PRODUCT_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange productManualExchange() {
        return new DirectExchange(MANUAL_EXCHANGE, true, false);
    }

    @Bean
    Queue productSnapshotQueue() {
        return QueueBuilder.durable(ProductRabbitListener.PRODUCT_QUEUE)
            .deadLetterExchange(MANUAL_EXCHANGE).deadLetterRoutingKey("FAILURE").build();
    }

    @Bean
    Queue productManualQueue() {
        return QueueBuilder.durable(MANUAL_QUEUE).quorum().build();
    }

    @Bean
    Declarables productSnapshotBindings(@Qualifier("productSnapshotQueue") Queue productSnapshotQueue,
                                        @Qualifier("productSnapshotExchange") DirectExchange productSnapshotExchange) {
        List<Declarable> bindings = new ArrayList<>();
        for (String eventType : EVENT_TYPES) {
            bindings.add(BindingBuilder.bind(productSnapshotQueue)
                .to(productSnapshotExchange).with(eventType));
        }
        return new Declarables(bindings);
    }

    @Bean
    Binding productManualBinding(@Qualifier("productManualQueue") Queue productManualQueue,
                                 @Qualifier("productManualExchange") DirectExchange productManualExchange) {
        return BindingBuilder.bind(productManualQueue).to(productManualExchange).with("FAILURE");
    }
}
