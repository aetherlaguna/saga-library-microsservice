package com.library.loans.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateLoanRequest(
    @NotNull UUID memberId,
    @NotNull UUID registeredBy,
    @NotEmpty(message = "Informe pelo menos um livro")
    Set<@NotNull UUID> bookIds,
    @NotNull @Future Instant dueAt
) {
}