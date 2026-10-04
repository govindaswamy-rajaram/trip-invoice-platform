package com.tripinvoice.itinerary.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tripinvoice.itinerary.outbox.OutboxEvent;
import com.tripinvoice.itinerary.repository.OutboxEventRepository;

/**
 * Relays outbox rows to RabbitMQ.
 *
 * Delivery is at-least-once: a row is marked published only after the broker confirms it. If the
 * process dies between the confirm and the commit, the event is sent again, so consumers must be
 * idempotent (use the eventId / messageId to de-duplicate).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final OutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties messaging;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;

    public OutboxPublisher(OutboxEventRepository outboxRepository, RabbitTemplate rabbitTemplate,
                           MessagingProperties messaging, PlatformTransactionManager transactionManager,
                           Clock clock, @Value("${itinerary.outbox.batch-size:50}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.messaging = messaging;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${itinerary.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        int published;
        do {
            Integer count = transactionTemplate.execute(status -> publishBatch());
            published = count == null ? 0 : count;
        } while (published == batchSize); // keep draining while full batches are found
    }

    private int publishBatch() {
        List<OutboxEvent> batch = outboxRepository.findUnpublishedForUpdate(PageRequest.ofSize(batchSize));
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.markPublished(clock.instant());
                published++;
            } catch (RuntimeException e) {
                // Broker unavailable or nack: keep the row unpublished and retry on the next poll.
                log.warn("Could not publish outbox event {} ({}): {}", event.getId(), event.getEventType(),
                        e.getMessage());
                break; // preserve ordering: do not publish later events before this one
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox event(s)", published);
        }
        return published;
    }

    private void send(OutboxEvent event) {
        Message message = MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.getId().toString())
                .setType(event.getEventType())
                .setHeader("aggregateId", event.getAggregateId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();

        // invoke() keeps send + confirm on one channel so we can wait for the broker's ack.
        rabbitTemplate.invoke(operations -> {
            operations.send(messaging.exchange(), event.getRoutingKey(), message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
