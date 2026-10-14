package com.library.loans.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.library.loans.domain.Loan;

public record LoanRequested(UUID memberId, List<UUID> bookIds, Instant dueAt) {
    public LoanRequested {
        bookIds = List.copyOf(bookIds);
    }

    public static LibraryEvent<LoanRequested> from(Loan loan) {
        return new LibraryEvent<>(UUID.randomUUID(), "LoanRequested", 1, loan.getCreatedAt(),
                loan.getId(), loan.getId(), loan.getId(), null,
                new LoanRequested(loan.getMemberId(), loan.getBookIds().stream().sorted().toList(),
                        loan.getDueAt()));
    }
}
