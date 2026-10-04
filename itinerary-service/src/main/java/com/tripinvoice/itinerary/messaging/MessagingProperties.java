package com.tripinvoice.itinerary.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConstructorBinding
@ConfigurationProperties(prefix = "itinerary.messaging")
public record MessagingProperties(
        @DefaultValue("itinerary.events") String exchange,
        @DefaultValue("itinerary.confirmed") String confirmedRoutingKey,
        @DefaultValue("itinerary.confirmed.debug") String debugQueue,
        @DefaultValue("false") boolean debugQueueEnabled) {
}
