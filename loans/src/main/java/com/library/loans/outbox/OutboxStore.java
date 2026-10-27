package com.library.loans.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.library.loans.messaging.LibraryEvent;
import com.library.loans.messaging.LoanRequested;
import com.library.loans.messaging.MessagingTopology;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxStore {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.library.loans.observability.OutboxTracing tracing;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public OutboxStore(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(LibraryEvent<LoanRequested> event) {
        append(event, MessagingTopology.LOAN_REQUESTED);
    }

    public void append(LibraryEvent<?> event, String routingKey) {
        jdbc.update("""
                INSERT INTO outbox_events
                (event_id, loan_id, event_type, schema_version, exchange_name, routing_key,
                 body, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, event.eventId(), event.loanId(), event.eventType(), event.schemaVersion(),
                MessagingTopology.EVENTS, routingKey,
                mapper.writeValueAsString(event), Timestamp.from(event.occurredAt()),
                Timestamp.from(event.occurredAt()));
        if (tracing != null) jdbc.update("update outbox_events set trace_headers=CAST(? AS jsonb) where event_id=?",
                mapper.writeValueAsString(tracing.capture()),event.eventId());
    }

    public Optional<OutboxEvent> lockNext(Instant now) {
        return jdbc.query("""
                SELECT * FROM outbox_events
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, created_at, event_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, index) -> new OutboxEvent(
                rs.getObject("event_id", UUID.class), rs.getObject("loan_id", UUID.class),
                rs.getString("event_type"), rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("exchange_name"),
                rs.getString("routing_key"), rs.getString("body"), rs.getLong("attempts"),
                rs.getInt("message_failures")), Timestamp.from(now)).stream().findFirst();
    }

    public void markSent(UUID eventId, Instant now) {
        jdbc.update("""
                UPDATE outbox_events SET status = 'SENT', sent_at = ?, last_error = NULL,
                attempts = attempts + 1 WHERE event_id = ?
                """, Timestamp.from(now), eventId);
    }

    public void recordFailure(OutboxEvent event, Instant now, PublishFailure.Code code,
            OutboxProperties properties) {
        int failures = event.messageFailures() + (code.infrastructure() ? 0 : 1);
        String status = failures >= properties.maxMessageFailures() ? "PARKED" : "PENDING";
        jdbc.update("""
                UPDATE outbox_events SET status = ?, attempts = attempts + 1,
                message_failures = ?, next_attempt_at = ?, last_error = ? WHERE event_id = ?
                """, status, failures,
                Timestamp.from(now.plusMillis(properties.retryDelay(event.attempts() + 1))),
                code.name(), event.eventId());
    }

    public boolean replayParked(UUID eventId, Instant now) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'PENDING', message_failures = 0,
                next_attempt_at = ?, last_error = NULL WHERE event_id = ? AND status = 'PARKED'
                """, Timestamp.from(now), eventId) == 1;
    }
}
