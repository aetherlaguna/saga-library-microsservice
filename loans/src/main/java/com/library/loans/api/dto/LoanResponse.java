package com.library.loans.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.library.loans.domain.LoanStatus;

public record LoanResponse(
    UUID id,
    UUID memberId,
    UUID registeredBy,
    Set<UUID> bookIds,
    LoanStatus status,
    Instant createdAt,
    Instant dueAt,
    Instant returnedAt
) {
    public LoanResponse {
        bookIds = Set.copyOf(bookIds);
    }
}