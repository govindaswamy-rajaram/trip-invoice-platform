package com.tripinvoice.itinerary.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tripinvoice.itinerary.domain.Itinerary;

/**
 * Published when an itinerary is confirmed. This is the contract with downstream consumers such
 * as invoice-service, so changes must stay backward compatible (bump schemaVersion otherwise).
 *
 * Data minimisation: the traveller's email address is intentionally not part of the event.
 */
public record ItineraryConfirmedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID itineraryId,
        String bookingReference,
        String customerId,
        String travelerName,
        String currency,
        BigDecimal totalAmount,
        List<Segment> segments) {

    public static final String EVENT_TYPE = "ItineraryConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public record Segment(String type, String description, LocalDate startDate, LocalDate endDate,
                          BigDecimal amount) {
    }

    public static ItineraryConfirmedEvent from(Itinerary itinerary, UUID eventId, Instant occurredAt) {
        List<Segment> segments = itinerary.getSegments().stream()
                .map(s -> new Segment(s.getType().name(), s.getDescription(), s.getStartDate(), s.getEndDate(),
                        s.getAmount()))
                .toList();
        return new ItineraryConfirmedEvent(
                eventId,
                EVENT_TYPE,
                SCHEMA_VERSION,
                occurredAt,
                itinerary.getId(),
                itinerary.getBookingReference(),
                itinerary.getCustomerId(),
                itinerary.getTravelerName(),
                itinerary.getCurrency(),
                itinerary.getTotalAmount(),
                segments);
    }
}
