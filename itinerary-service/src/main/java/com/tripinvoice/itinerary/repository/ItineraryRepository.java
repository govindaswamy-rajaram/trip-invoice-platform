package com.tripinvoice.itinerary.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tripinvoice.itinerary.domain.Itinerary;

public interface ItineraryRepository extends JpaRepository<Itinerary, UUID> {

    boolean existsByBookingReference(String bookingReference);

    @EntityGraph(attributePaths = "segments")
    Optional<Itinerary> findWithSegmentsById(UUID id);
}
