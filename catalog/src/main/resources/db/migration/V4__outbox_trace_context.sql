ALTER TABLE outbox_events ADD COLUMN trace_headers JSONB NOT NULL DEFAULT '{}'::jsonb;
