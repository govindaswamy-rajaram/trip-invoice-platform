-- Plain SQL schema for itinerary-service.
-- Executed by Spring (spring.sql.init) on every start, so every statement is idempotent.
-- Hibernate only validates this schema (ddl-auto: validate); it never changes it.

CREATE TABLE IF NOT EXISTS itinerary (
    id                UUID          PRIMARY KEY,
    booking_reference VARCHAR(20)   NOT NULL,
    customer_id       VARCHAR(64)   NOT NULL,
    traveler_name     VARCHAR(120)  NOT NULL,
    traveler_email    VARCHAR(254)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    total_amount      NUMERIC(12,2) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_itinerary_booking_reference UNIQUE (booking_reference)
);

CREATE TABLE IF NOT EXISTS travel_segment (
    id            UUID          PRIMARY KEY,
    itinerary_id  UUID          NOT NULL REFERENCES itinerary (id) ON DELETE CASCADE,
    segment_type  VARCHAR(20)   NOT NULL,
    description   VARCHAR(200)  NOT NULL,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    amount        NUMERIC(12,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_travel_segment_itinerary ON travel_segment (itinerary_id);

-- Transactional outbox: written in the same transaction as the business data,
-- then relayed to RabbitMQ by OutboxPublisher.
CREATE TABLE IF NOT EXISTS outbox_event (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    routing_key    VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
