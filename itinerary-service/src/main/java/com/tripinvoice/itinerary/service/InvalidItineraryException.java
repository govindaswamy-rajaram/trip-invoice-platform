package com.tripinvoice.itinerary.service;

public class InvalidItineraryException extends RuntimeException {
    public InvalidItineraryException(String message) {
        super(message);
    }
}
