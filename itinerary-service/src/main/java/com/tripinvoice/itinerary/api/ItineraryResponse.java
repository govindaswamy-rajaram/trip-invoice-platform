package com.tripinvoice.itinerary.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tripinvoice.itinerary.domain.Itinerary;
import com.tripinvoice.itinerary.domain.TravelSegment;

public record ItineraryResponse(
        UUID id,
        String bookingReference,
        String customerId,
        String travelerName,
        String travelerEmail,
        String status,
        String currency,
        BigDecimal totalAmount,
        Instant createdAt,
        List<SegmentResponse> segments) {

    public record SegmentResponse(String type, String description, LocalDate startDate, LocalDate endDate,
                                  BigDecimal amount) {
        static SegmentResponse from(TravelSegment segment) {
            return new SegmentResponse(segment.getType().name(), segment.getDescription(), segment.getStartDate(),
                    segment.getEndDate(), segment.getAmount());
        }
    }

    public static ItineraryResponse from(Itinerary itinerary) {
        return new ItineraryResponse(
                itinerary.getId(),
                itinerary.getBookingReference(),
                itinerary.getCustomerId(),
                itinerary.getTravelerName(),
                itinerary.getTravelerEmail(),
                itinerary.getStatus().name(),
                itinerary.getCurrency(),
                itinerary.getTotalAmount(),
                itinerary.getCreatedAt(),
                itinerary.getSegments().stream().map(SegmentResponse::from).toList());
    }
}
