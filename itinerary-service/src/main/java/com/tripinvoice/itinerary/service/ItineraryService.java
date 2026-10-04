package com.tripinvoice.itinerary.service;

import java.time.Clock;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripinvoice.itinerary.api.CreateItineraryRequest;
import com.tripinvoice.itinerary.api.CreateItineraryRequest.SegmentRequest;
import com.tripinvoice.itinerary.api.ItineraryResponse;
import com.tripinvoice.itinerary.domain.Itinerary;
import com.tripinvoice.itinerary.domain.TravelSegment;
import com.tripinvoice.itinerary.messaging.ItineraryConfirmedEvent;
import com.tripinvoice.itinerary.messaging.MessagingProperties;
import com.tripinvoice.itinerary.outbox.OutboxEvent;
import com.tripinvoice.itinerary.repository.ItineraryRepository;
import com.tripinvoice.itinerary.repository.OutboxEventRepository;

@Service
public class ItineraryService {

    private static final String AGGREGATE_TYPE = "Itinerary";

    private final ItineraryRepository itineraryRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MessagingProperties messaging;

    public ItineraryService(ItineraryRepository itineraryRepository, OutboxEventRepository outboxRepository,
                            ObjectMapper objectMapper, Clock clock, MessagingProperties messaging) {
        this.itineraryRepository = itineraryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.messaging = messaging;
    }

    /**
     * Stores the itinerary and, in the same database transaction, records an ItineraryConfirmed
     * event in the outbox. The event is relayed to RabbitMQ afterwards by the OutboxPublisher.
     */
    @Transactional
    public ItineraryResponse confirm(CreateItineraryRequest request) {
        String bookingReference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        requireKnownCurrency(request.currency());

        if (itineraryRepository.existsByBookingReference(bookingReference)) {
            throw new DuplicateBookingException(bookingReference);
        }

        Itinerary itinerary = Itinerary.confirmed(
                bookingReference,
                request.customerId().trim(),
                request.travelerName().trim(),
                request.travelerEmail().trim(),
                request.currency(),
                clock.instant());

        for (SegmentRequest segment : request.segments()) {
            if (segment.endDate().isBefore(segment.startDate())) {
                throw new InvalidItineraryException(
                        "Segment '" + segment.description() + "' ends before it starts");
            }
            itinerary.addSegment(new TravelSegment(segment.type(), segment.description().trim(),
                    segment.startDate(), segment.endDate(), segment.amount()));
        }

        Itinerary saved = itineraryRepository.saveAndFlush(itinerary);

        ItineraryConfirmedEvent event = ItineraryConfirmedEvent.from(saved, UUID.randomUUID(), clock.instant());
        outboxRepository.save(OutboxEvent.pending(
                event.eventId(),
                AGGREGATE_TYPE,
                saved.getId(),
                ItineraryConfirmedEvent.EVENT_TYPE,
                messaging.confirmedRoutingKey(),
                toJson(event),
                event.occurredAt()));

        return ItineraryResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ItineraryResponse get(UUID id) {
        return itineraryRepository.findWithSegmentsById(id)
                .map(ItineraryResponse::from)
                .orElseThrow(() -> new ItineraryNotFoundException(id));
    }

    private static void requireKnownCurrency(String code) {
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidItineraryException("Unknown currency code: " + code);
        }
    }

    private String toJson(ItineraryConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + event.eventType(), e);
        }
    }
}
