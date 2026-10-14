CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    loan_id UUID NOT NULL REFERENCES loans(id),
    event_type VARCHAR(80) NOT NULL,
    schema_version SMALLINT NOT NULL CHECK (schema_version > 0),
    exchange_name VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'PARKED')),
    attempts BIGINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    message_failures INTEGER NOT NULL DEFAULT 0 CHECK (message_failures >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(50),
    CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';
