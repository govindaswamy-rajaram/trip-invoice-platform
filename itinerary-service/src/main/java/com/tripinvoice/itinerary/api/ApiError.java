package com.tripinvoice.itinerary.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Consistent JSON error body returned by every failing request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String title, String detail, Map<String, String> errors) {

    public static ApiError of(int status, String title, String detail) {
        return new ApiError(status, title, detail, null);
    }
}
