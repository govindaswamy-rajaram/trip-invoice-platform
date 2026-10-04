package com.tripinvoice.itinerary.service;

import java.util.UUID;

public class ItineraryNotFoundException extends RuntimeException {
    public ItineraryNotFoundException(UUID id) {
        super("No itinerary found with id " + id);
    }
}
