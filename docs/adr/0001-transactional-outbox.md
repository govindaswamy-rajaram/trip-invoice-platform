# ADR 0001: Use a transactional outbox to publish ItineraryConfirmed

- Status: Accepted
- Date: 2026-10-04

## Context

When a booking arrives, itinerary-service must store the itinerary **and** tell other services
(invoice-service) about it. Writing to PostgreSQL and publishing to RabbitMQ are two separate
systems with no shared transaction, so doing both in the request handler can fail half-way:

- DB commit succeeds, publish fails: the itinerary exists but no invoice is ever generated.
- Publish succeeds, DB commit fails: an invoice is generated for an itinerary that does not exist.

For an invoicing domain, silently losing or inventing events is not acceptable.

## Decision

Use the **transactional outbox** pattern:

1. In one DB transaction, insert the itinerary and an `outbox_event` row containing the serialised event.
2. A scheduled `OutboxPublisher` reads unpublished rows (`FOR UPDATE SKIP LOCKED`), publishes them to
   RabbitMQ, waits for the broker's publisher confirm, then marks the row as published.

## Consequences

- No lost events: the event exists if and only if the itinerary was committed.
- Delivery is **at-least-once**. A crash between broker confirm and DB commit re-sends the event, so
  consumers must be idempotent (de-duplicate on `eventId` / AMQP `messageId`).
- Events appear with a small delay (the poll interval, 1 s by default).
- Several instances can run the relay at the same time thanks to `SKIP LOCKED`.
- Published rows accumulate and need periodic clean-up (planned: a retention job).

## Alternatives considered

- **Publish inside the request handler:** simplest, but has the dual-write problem above.
- **Change data capture (Debezium):** removes polling and delay, but adds infrastructure that is
  disproportionate for this project. Could replace the poller later without changing the schema.
- **Distributed transaction (XA):** poor broker support and operational cost.
