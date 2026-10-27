CREATE TABLE loan_sagas (
    loan_id UUID PRIMARY KEY REFERENCES loans(id) ON DELETE CASCADE,
    state VARCHAR(30) NOT NULL CHECK (state IN ('WAITING', 'WAITING_RELEASE', 'COMPLETED')),
    deadline TIMESTAMPTZ NOT NULL,
    release_event_id UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (state <> 'WAITING_RELEASE' OR release_event_id IS NOT NULL)
);
INSERT INTO loan_sagas(loan_id,state,deadline,updated_at)
SELECT id, CASE WHEN status='PENDING' THEN 'WAITING' ELSE 'COMPLETED' END,
       created_at + interval '5 minutes', CURRENT_TIMESTAMP FROM loans;
CREATE INDEX ix_saga_recovery ON loan_sagas(state, deadline) WHERE state <> 'COMPLETED';

CREATE TABLE release_result_inbox (
    event_id UUID PRIMARY KEY,
    loan_id UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    fingerprint CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
