package com.tripinvoice.itinerary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripinvoice.itinerary.api.CreateItineraryRequest;
import com.tripinvoice.itinerary.api.CreateItineraryRequest.SegmentRequest;
import com.tripinvoice.itinerary.api.ItineraryResponse;
import com.tripinvoice.itinerary.domain.Itinerary;
import com.tripinvoice.itinerary.domain.SegmentType;
import com.tripinvoice.itinerary.messaging.MessagingProperties;
import com.tripinvoice.itinerary.outbox.OutboxEvent;
import com.tripinvoice.itinerary.repository.ItineraryRepository;
import com.tripinvoice.itinerary.repository.OutboxEventRepository;

@ExtendWith(MockitoExtension.class)
class ItineraryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    @Mock
    private ItineraryRepository itineraryRepository;
    @Mock
    private OutboxEventRepository outboxRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ItineraryService service;

    @BeforeEach
    void setUp() {
        service = new ItineraryService(itineraryRepository, outboxRepository, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new MessagingProperties("itinerary.events", "itinerary.confirmed", "itinerary.confirmed.debug", false));
    }

    @Test
    void computesTotalFromSegmentsAndWritesOutboxEventInSameCall() throws Exception {
        when(itineraryRepository.saveAndFlush(any(Itinerary.class))).thenAnswer(invocation -> {
            Itinerary itinerary = invocation.getArgument(0);
            ReflectionTestUtils.setField(itinerary, "id", UUID.randomUUID()); // Hibernate does this on persist
            return itinerary;
        });

        ItineraryResponse response = service.confirm(validRequest("ab-1234"));

        assertThat(response.bookingReference()).isEqualTo("AB-1234"); // normalised
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.totalAmount()).isEqualByComparingTo("480.00");
        assertThat(response.segments()).hasSize(2);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo("ItineraryConfirmed");
        assertThat(outbox.getRoutingKey()).isEqualTo("itinerary.confirmed");
        assertThat(outbox.getAggregateId()).isEqualTo(response.id());

        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        assertThat(payload.get("eventType").asText()).isEqualTo("ItineraryConfirmed");
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("bookingReference").asText()).isEqualTo("AB-1234");
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("480.00");
        assertThat(payload.get("segments")).hasSize(2);
        assertThat(payload.has("travelerEmail")).isFalse(); // data minimisation
    }

    @Test
    void rejectsDuplicateBookingReference() {
        when(itineraryRepository.existsByBookingReference("AB-1234")).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(validRequest("AB-1234")))
                .isInstanceOf(DuplicateBookingException.class);

        verify(itineraryRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void rejectsUnknownCurrency() {
        CreateItineraryRequest request = new CreateItineraryRequest("AB-1234", "CUST-1", "Jane Doe",
                "jane@example.com", "ZZZ", List.of(flight("100.00")));

        assertThatThrownBy(() -> service.confirm(request))
                .isInstanceOf(InvalidItineraryException.class)
                .hasMessageContaining("ZZZ");
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void rejectsSegmentThatEndsBeforeItStarts() {
        SegmentRequest backwards = new SegmentRequest(SegmentType.HOTEL, "Bad dates",
                LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10), new BigDecimal("50.00"));
        CreateItineraryRequest request = new CreateItineraryRequest("AB-1234", "CUST-1", "Jane Doe",
                "jane@example.com", "EUR", List.of(backwards));

        assertThatThrownBy(() -> service.confirm(request))
                .isInstanceOf(InvalidItineraryException.class)
                .hasMessageContaining("ends before it starts");
        verify(outboxRepository, never()).save(any());
    }

    private static CreateItineraryRequest validRequest(String bookingReference) {
        return new CreateItineraryRequest(bookingReference, "CUST-1", "Jane Doe", "jane@example.com", "EUR",
                List.of(flight("220.50"), hotel("259.50")));
    }

    private static SegmentRequest flight(String amount) {
        return new SegmentRequest(SegmentType.FLIGHT, "AMS-CPH flight",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), new BigDecimal(amount));
    }

    private static SegmentRequest hotel(String amount) {
        return new SegmentRequest(SegmentType.HOTEL, "2 nights in Copenhagen",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), new BigDecimal(amount));
    }
}
