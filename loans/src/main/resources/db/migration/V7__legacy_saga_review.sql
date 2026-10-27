-- Loans created before outbox adoption have no causal request to compensate.
-- Preserve them for explicit review instead of blocking every timeout batch.
ALTER TABLE loan_sagas DROP CONSTRAINT loan_sagas_state_check;
ALTER TABLE loan_sagas ADD CONSTRAINT loan_sagas_state_check
    CHECK (state IN ('WAITING', 'WAITING_RELEASE', 'COMPLETED', 'MANUAL_REVIEW'));
UPDATE loan_sagas s SET state='MANUAL_REVIEW', updated_at=CURRENT_TIMESTAMP
WHERE s.state='WAITING' AND NOT EXISTS (
    SELECT 1 FROM outbox_events o WHERE o.loan_id=s.loan_id AND o.event_type='LoanRequested'
);
