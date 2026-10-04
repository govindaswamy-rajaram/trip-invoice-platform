package com.tripinvoice.itinerary.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.tripinvoice.itinerary.domain.SegmentType;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * A confirmed booking. The total is deliberately not part of the request: it is computed
 * from the segments on the server so a client can never send an inconsistent amount.
 */
public record CreateItineraryRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{3,20}$", message = "must be 3-20 letters, digits or hyphens")
        String bookingReference,
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Size(max = 120) String travelerName,
        @NotBlank @Email @Size(max = 254) String travelerEmail,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code, e.g. EUR")
        String currency,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull SegmentRequest> segments) {

    public record SegmentRequest(
            @NotNull SegmentType type,
            @NotBlank @Size(max = 200) String description,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }
}
