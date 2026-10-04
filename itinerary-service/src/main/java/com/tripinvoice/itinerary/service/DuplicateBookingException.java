package com.tripinvoice.itinerary.service;

public class DuplicateBookingException extends RuntimeException {
    public DuplicateBookingException(String bookingReference) {
        super("An itinerary with booking reference " + bookingReference + " already exists");
    }
}
