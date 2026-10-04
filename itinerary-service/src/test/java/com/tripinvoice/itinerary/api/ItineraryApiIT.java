package com.tripinvoice.itinerary.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end test against real PostgreSQL and RabbitMQ. Needs Docker. Run with: mvn verify
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "itinerary.outbox.poll-interval-ms=200",
        "itinerary.messaging.debug-queue-enabled=true"
})
@Testcontainers
class ItineraryApiIT {

    private static final String DEBUG_QUEUE = "itinerary.confirmed.debug";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    // Spring Boot 2.x has no @ServiceConnection, so the container ports are wired in by hand.
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void storesBookingAndPublishesItineraryConfirmedEvent() throws Exception {
        String reference = uniqueReference();

        ResponseEntity<String> created = rest.postForEntity("/api/v1/itineraries", validBooking(reference), String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        JsonNode body = objectMapper.readTree(created.getBody());
        assertThat(body.get("bookingReference").asText()).isEqualTo(reference);
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo("480.00");

        // The event is relayed asynchronously by the outbox publisher.
        Message message = receiveEventFor(reference);
        assertThat(message).as("ItineraryConfirmed event on %s", DEBUG_QUEUE).isNotNull();
        JsonNode event = objectMapper.readTree(message.getBody());
        assertThat(event.get("eventType").asText()).isEqualTo("ItineraryConfirmed");
        assertThat(event.get("itineraryId").asText()).isEqualTo(body.get("id").asText());
        assertThat(event.get("totalAmount").decimalValue()).isEqualByComparingTo("480.00");
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
    }

    @Test
    void storedItineraryCanBeReadBack() throws Exception {
        String reference = uniqueReference();
        ResponseEntity<String> created = rest.postForEntity("/api/v1/itineraries", validBooking(reference), String.class);
        String id = objectMapper.readTree(created.getBody()).get("id").asText();

        ResponseEntity<String> fetched = rest.getForEntity("/api/v1/itineraries/" + id, String.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(fetched.getBody());
        assertThat(body.get("bookingReference").asText()).isEqualTo(reference);
        assertThat(body.get("segments")).hasSize(2);
    }

    @Test
    void unknownItineraryReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/itineraries/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateBookingReferenceReturns409() {
        String reference = uniqueReference();
        rest.postForEntity("/api/v1/itineraries", validBooking(reference), String.class);

        ResponseEntity<String> second = rest.postForEntity("/api/v1/itineraries", validBooking(reference), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidPayloadReturns400WithFieldErrors() throws Exception {
        Map<String, Object> invalid = Map.of(
                "bookingReference", "x",
                "customerId", "CUST-1",
                "travelerName", "Jane Doe",
                "travelerEmail", "not-an-email",
                "currency", "euro",
                "segments", List.of());

        ResponseEntity<String> response = rest.postForEntity("/api/v1/itineraries", invalid, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode errors = objectMapper.readTree(response.getBody()).get("errors");
        assertThat(errors.has("bookingReference")).isTrue();
        assertThat(errors.has("travelerEmail")).isTrue();
        assertThat(errors.has("currency")).isTrue();
        assertThat(errors.has("segments")).isTrue();
    }

    /** Reads from the debug queue until the event for this booking shows up (other tests share the queue). */
    private Message receiveEventFor(String reference) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(DEBUG_QUEUE, 1_000);
            if (message != null
                    && reference.equals(objectMapper.readTree(message.getBody()).get("bookingReference").asText())) {
                return message;
            }
        }
        return null;
    }

    private static String uniqueReference() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static Map<String, Object> validBooking(String reference) {
        return Map.of(
                "bookingReference", reference,
                "customerId", "CUST-1001",
                "travelerName", "Jane Doe",
                "travelerEmail", "jane.doe@example.com",
                "currency", "EUR",
                "segments", List.of(
                        Map.of("type", "FLIGHT", "description", "AMS-CPH flight",
                                "startDate", "2026-11-10", "endDate", "2026-11-10", "amount", "220.50"),
                        Map.of("type", "HOTEL", "description", "2 nights in Copenhagen",
                                "startDate", "2026-11-10", "endDate", "2026-11-12", "amount", "259.50")));
    }
}
