package com.example.campusmarket.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/** 领域事件交换机与 durable quorum 队列。 */
@Configuration
@Profile("!test")
public class RabbitTopology {
    public static final String EVENT_EXCHANGE = "campus.market.events";
    public static final String EVENT_QUEUE = "campus.market.events.order";
    public static final String WARRANTY_QUEUE = "campus.market.events.warranty";
    public static final String MANUAL_EXCHANGE = "campus.market.manual";
    public static final String MANUAL_QUEUE = "campus.market.manual.failure";
    public static final String PRODUCT_EXCHANGE = "campus.product.snapshot";
    public static final String PRODUCT_QUEUE = "campus.product.read";

    @Bean
    DirectExchange campusMarketEventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange campusMarketManualExchange() {
        return new DirectExchange(MANUAL_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange campusProductSnapshotExchange() {
        return new DirectExchange(PRODUCT_EXCHANGE, true, false);
    }

    @Bean
    Queue campusMarketOrderEventQueue() {
        return QueueBuilder.durable(EVENT_QUEUE).quorum()
            .deadLetterExchange(MANUAL_EXCHANGE).deadLetterRoutingKey("FAILURE").build();
    }

    @Bean
    Queue campusMarketWarrantyEventQueue() {
        return QueueBuilder.durable(WARRANTY_QUEUE).quorum()
            .deadLetterExchange(MANUAL_EXCHANGE).deadLetterRoutingKey("FAILURE").build();
    }

    @Bean
    Queue campusMarketManualQueue() {
        return QueueBuilder.durable(MANUAL_QUEUE).quorum().build();
    }

    @Bean
    Queue campusProductReadQueue() {
        return QueueBuilder.durable(PRODUCT_QUEUE)
                .deadLetterExchange(MANUAL_EXCHANGE)
                .deadLetterRoutingKey("FAILURE")
                .build();
    }

    @Bean
    Declarables campusMarketEventBindings(Queue campusMarketOrderEventQueue,
                                          DirectExchange campusMarketEventExchange) {
        List<Declarable> bindings = new ArrayList<>();
        for (String eventType : ORDER_EVENT_TYPES) {
            bindings.add(BindingBuilder.bind(campusMarketOrderEventQueue)
                .to(campusMarketEventExchange).with(eventType));
        }
        return new Declarables(bindings);
    }

    @Bean
    Declarables campusMarketWarrantyEventBindings(Queue campusMarketWarrantyEventQueue,
                                                  DirectExchange campusMarketEventExchange) {
        return new Declarables(BindingBuilder.bind(campusMarketWarrantyEventQueue)
            .to(campusMarketEventExchange).with("WARRANTY_REFUND_REQUESTED"));
    }

    @Bean
    Binding campusMarketManualBinding(Queue campusMarketManualQueue, DirectExchange campusMarketManualExchange) {
        return BindingBuilder.bind(campusMarketManualQueue).to(campusMarketManualExchange).with("FAILURE");
    }

    @Bean
    Declarables campusProductSnapshotBindings(Queue campusProductReadQueue,
                                               DirectExchange campusProductSnapshotExchange) {
        List<Declarable> bindings = new ArrayList<>();
        for (String eventType : PRODUCT_EVENT_TYPES) {
            bindings.add(BindingBuilder.bind(campusProductReadQueue)
                .to(campusProductSnapshotExchange).with(eventType));
        }
        return new Declarables(bindings);
    }

    private static final List<String> ORDER_EVENT_TYPES = List.of(
        "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED", "LISTING_OFF_SALE", "LISTING_SOLD_OUT",
        "INVENTORY_CHANGED", "ORDER_CREATED", "ORDER_CANCELLED", "ORDER_PAID", "ORDER_HANDOFF_CONFIRMED",
        "ORDER_RECEIPT_CONFIRMED", "ORDER_DISPUTED", "ORDER_REFUNDING_CANCEL", "ORDER_REFUNDED",
        "ORDER_SETTLED", "ORDER_TRIAL_ELAPSED", "PAYMENT_CREATED", "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "PAYMENT_CALLBACK_RECEIVED",
        "REFUND_REQUESTED", "REFUND_SUCCEEDED", "REFUND_FAILED", "DISPUTE_CREATED", "DISPUTE_RESOLVED", "DISPUTE_SLA_ALERT",
        "SELLER_OBLIGATION_CREATED", "SELLER_OBLIGATION_FUNDED", "SELLER_RESTRICTION_ACTIVATED", "SELLER_RESTRICTION_CLEARED", "SELLER_OBLIGATION_DEDUCTED", "SELLER_OBLIGATION_EXPIRED",
        "SETTLEMENT_CREATED", "REVIEW_CREATED");

    private static final List<String> PRODUCT_EVENT_TYPES = List.of(
        "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED", "LISTING_OFF_SALE",
        "LISTING_SOLD_OUT", "INVENTORY_CHANGED");
}
