package com.library.catalog.messaging;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LibraryEvent<T>(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        UUID loanId, UUID sagaId, UUID correlationId, UUID causationId, T payload) {}
