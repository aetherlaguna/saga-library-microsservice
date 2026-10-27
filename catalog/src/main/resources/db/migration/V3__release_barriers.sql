CREATE TABLE release_barriers (
    loan_id UUID PRIMARY KEY,
    request_event_id UUID NOT NULL,
    release_event_id UUID NOT NULL UNIQUE,
    fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check
    CHECK (status IN ('RESERVED', 'REJECTED', 'RELEASED'));
-- Release can precede the original request, so outbox results need no local reservation.
ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_loan_id_fkey;
