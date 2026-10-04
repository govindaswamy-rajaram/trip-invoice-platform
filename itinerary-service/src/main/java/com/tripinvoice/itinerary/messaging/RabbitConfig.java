package com.tripinvoice.itinerary.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /** The producer owns the exchange. Consumers declare and bind their own queues. */
    @Bean
    TopicExchange itineraryEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "itinerary.messaging", name = "debug-queue-enabled", havingValue = "true")
    Queue itineraryConfirmedDebugQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.debugQueue()).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "itinerary.messaging", name = "debug-queue-enabled", havingValue = "true")
    Binding itineraryConfirmedDebugBinding(Queue itineraryConfirmedDebugQueue, TopicExchange itineraryEventsExchange,
                                           MessagingProperties properties) {
        return BindingBuilder.bind(itineraryConfirmedDebugQueue)
                .to(itineraryEventsExchange)
                .with(properties.confirmedRoutingKey());
    }
}
