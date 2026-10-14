package com.library.loans.messaging;

import java.time.Instant;
import java.util.UUID;

public record LibraryEvent<T>(
        UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        UUID loanId, UUID sagaId, UUID correlationId, UUID causationId, T payload) {
}
