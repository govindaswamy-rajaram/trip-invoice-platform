package com.tripinvoice.itinerary.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.tripinvoice.itinerary.service.DuplicateBookingException;
import com.tripinvoice.itinerary.service.InvalidItineraryException;
import com.tripinvoice.itinerary.service.ItineraryNotFoundException;

/** Maps exceptions to a consistent JSON error body (see {@link ApiError}). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DuplicateBookingException.class)
    ResponseEntity<ApiError> handleDuplicate(DuplicateBookingException ex) {
        return build(HttpStatus.CONFLICT, "Duplicate booking", ex.getMessage());
    }

    // Two concurrent requests with the same booking reference can both pass the existence check;
    // the unique constraint then rejects the second one.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrityViolation(DataIntegrityViolationException ex) {
        return build(HttpStatus.CONFLICT, "Conflict",
                "The itinerary conflicts with existing data, for example a booking reference that already exists.");
    }

    @ExceptionHandler(ItineraryNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ItineraryNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Itinerary not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidItineraryException.class)
    ResponseEntity<ApiError> handleInvalid(InvalidItineraryException ex) {
        return build(HttpStatus.BAD_REQUEST, "Invalid itinerary", ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ApiError body = new ApiError(HttpStatus.BAD_REQUEST.value(), "Validation error",
                "Request validation failed", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(body);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), title, detail));
    }
}
