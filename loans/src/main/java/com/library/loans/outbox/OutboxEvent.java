package com.library.loans.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID eventId, UUID loanId, String eventType, int schemaVersion,
        Instant createdAt, String exchangeName, String routingKey, String body,
        long attempts, int messageFailures) {
}
