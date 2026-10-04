# trip-invoice-platform

An event-driven travel invoicing demo, built to explore reliable messaging between microservices.
A confirmed travel itinerary comes in, and an invoice is produced for it exactly once, even when
parts of the system fail.

> All data and business rules here are invented for demonstration. This repository contains no
> code, data or designs from any employer or client.

## Services

| Service | Status | Responsibility |
|---|---|---|
| `itinerary-service` | Done (v0.1) | Accepts bookings over REST, stores them, publishes `ItineraryConfirmed` |
| `invoice-service` | Planned | Consumes `ItineraryConfirmed` idempotently, calculates and stores invoices |
| `notification-service` | Optional | Consumes `InvoiceGenerated` |

## Architecture

```mermaid
sequenceDiagram
    participant C as Client
    participant I as itinerary-service
    participant DB as PostgreSQL
    participant R as RabbitMQ
    participant V as invoice-service (planned)

    C->>I: POST /api/v1/itineraries
    I->>DB: BEGIN; insert itinerary + segments + outbox_event; COMMIT
    I-->>C: 201 Created
    loop every second
        I->>DB: select unpublished outbox rows (SKIP LOCKED)
        I->>R: publish ItineraryConfirmed (wait for confirm)
        I->>DB: mark row published
    end
    R->>V: ItineraryConfirmed
```

## Tech stack

Java 17, Spring Boot 2.7, Spring Data JPA / Hibernate, PostgreSQL 16, RabbitMQ 3.13,
Testcontainers, Docker Compose.

## Run it locally

Prerequisites: JDK 17, Maven 3.9+, Docker.

```bash
# 1. start PostgreSQL and RabbitMQ
docker compose up -d postgres rabbitmq

# 2. run the service
cd itinerary-service
mvn spring-boot:run
```

Or run everything in containers: `docker compose up --build`.

Create a booking:

```bash
curl -i -X POST http://localhost:8081/api/v1/itineraries \
  -H 'Content-Type: application/json' \
  -d '{
    "bookingReference": "AB-1234",
    "customerId": "CUST-1001",
    "travelerName": "Jane Doe",
    "travelerEmail": "jane.doe@example.com",
    "currency": "EUR",
    "segments": [
      {"type": "FLIGHT", "description": "AMS-CPH flight", "startDate": "2026-11-10", "endDate": "2026-11-10", "amount": 220.50},
      {"type": "HOTEL",  "description": "2 nights in Copenhagen", "startDate": "2026-11-10", "endDate": "2026-11-12", "amount": 259.50}
    ]
  }'
```

Then open the RabbitMQ UI at http://localhost:15672 (guest / guest), go to
**Queues -> itinerary.confirmed.debug -> Get messages** to see the `ItineraryConfirmed` event.

## API

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/itineraries` | `201` with `Location` header; `400` validation error; `409` duplicate booking reference |
| `GET` | `/api/v1/itineraries/{id}` | `200`, or `404` |
| `GET` | `/actuator/health` | Liveness and readiness, including DB and RabbitMQ |

Errors use one JSON shape: `{"status": 400, "title": "...", "detail": "...", "errors": {"field": "message"}}` (`errors` only for validation failures).

## Event: `ItineraryConfirmed` (schema version 1)

Exchange `itinerary.events` (topic), routing key `itinerary.confirmed`.

```json
{
  "eventId": "6f1c...",
  "eventType": "ItineraryConfirmed",
  "schemaVersion": 1,
  "occurredAt": "2026-10-04T10:00:00Z",
  "itineraryId": "0b7e...",
  "bookingReference": "AB-1234",
  "customerId": "CUST-1001",
  "travelerName": "Jane Doe",
  "currency": "EUR",
  "totalAmount": 480.00,
  "segments": [
    {"type": "FLIGHT", "description": "AMS-CPH flight", "startDate": "2026-11-10", "endDate": "2026-11-10", "amount": 220.50}
  ]
}
```

The AMQP `messageId` equals `eventId`. Consumers should use it to de-duplicate.

## Design decisions

- **Transactional outbox** instead of publishing in the request handler, so a stored itinerary and its event can never diverge. See [ADR 0001](docs/adr/0001-transactional-outbox.md).
- **At-least-once delivery.** The relay marks a row published only after the broker confirms it. Consumers must be idempotent.
- **Totals are computed on the server** from the segments. Clients cannot send an inconsistent amount.
- **Money** uses `BigDecimal` / `NUMERIC(12,2)` with ISO 4217 currency codes, never floating point.
- **Idempotent bookings:** `bookingReference` is unique (case-insensitive via normalisation). A repeat returns `409`, including under concurrent requests (unique constraint as the final guard).
- **Data minimisation:** the traveller's email is stored but deliberately left out of the event.
- **Plain SQL schema** in `db/schema.sql`, run by Spring on start-up with idempotent `IF NOT EXISTS` statements. Hibernate only validates it (`ddl-auto: validate`). The trade-off: there is no versioned migration history, so a schema change means editing the script or adding a manual `ALTER`.
- **Competing relays:** `FOR UPDATE SKIP LOCKED` allows several instances to run the publisher safely.

## Tests

```bash
mvn clean install                  # compiles and runs the unit tests (no Docker, no Testcontainers download)
mvn clean verify -Pintegration     # also runs the *IT tests with Testcontainers (PostgreSQL + RabbitMQ), needs Docker
```

The integration tests live in `*IT.java`. They are excluded from the default build and only compiled
when the `integration` profile is active.

## Roadmap

- [ ] `invoice-service` with an idempotent RabbitMQ consumer, retry with backoff and a dead-letter queue
- [ ] Reconciliation job: find itineraries without an invoice
- [ ] Outbox clean-up with a PL/pgSQL function, plus index tuning checked with `EXPLAIN ANALYZE`
- [ ] GitHub Actions pipeline: build, JUnit tests, Docker image
- [ ] Kubernetes manifests and a deployment guide for AWS
