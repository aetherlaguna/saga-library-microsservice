CREATE TABLE catalog_result_inbox (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE catalog_result_decisions (
    loan_id UUID PRIMARY KEY REFERENCES loans(id),
    outcome_hash CHAR(64) NOT NULL,
    event_type VARCHAR(80) NOT NULL CHECK (event_type IN ('BooksReserved', 'BooksReservationRejected')),
    reservation_id UUID,
    reason_code VARCHAR(80),
    CHECK ((event_type = 'BooksReserved') = (reservation_id IS NOT NULL)),
    event_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
