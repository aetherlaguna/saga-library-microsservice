package com.library.catalog.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID eventId, UUID loanId, String eventType, int schemaVersion,
        UUID sagaId, UUID correlationId, UUID causationId, Instant createdAt, String exchangeName, String routingKey, String body,
        long attempts, int messageFailures) {
}
