CREATE TABLE inbox_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE reservations (
    loan_id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    member_id UUID NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'REJECTED')),
    reason_code VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'REJECTED') = (reason_code IS NOT NULL))
);

CREATE TABLE reservation_books (
    reservation_id UUID NOT NULL REFERENCES reservations(reservation_id),
    book_id UUID NOT NULL REFERENCES books(id),
    PRIMARY KEY (reservation_id, book_id)
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    loan_id UUID NOT NULL REFERENCES reservations(loan_id),
    saga_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version SMALLINT NOT NULL CHECK (schema_version > 0),
    exchange_name VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'PARKED')),
    attempts BIGINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    message_failures INTEGER NOT NULL DEFAULT 0 CHECK (message_failures >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(50),
    CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_catalog_outbox_pending ON outbox_events(next_attempt_at, created_at)
    WHERE status = 'PENDING';
