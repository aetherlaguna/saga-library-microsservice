package com.library.loans.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.loans.api.dto.LoanResponse;
import com.library.loans.domain.Loan;
import com.library.loans.repository.LoanRepository;

@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final LoanRepository loanRepository;
    private final Clock clock;

    public LoanService(LoanRepository loanRepository, Clock clock) {
        this.loanRepository = loanRepository;
        this.clock = clock;
    }

    @Transactional
    public LoanResponse create(
            UUID memberId,
            UUID registeredBy,
            Set<UUID> bookIds,
            Instant dueAt) {

        Loan loan = Loan.create(
                memberId,
                registeredBy,
                bookIds,
                Instant.now(clock),
                dueAt);

        Loan savedLoan = loanRepository.save(loan);

        log.debug(
                "Empréstimo preparado para persistência: loanId={}, status={}",
                savedLoan.getId(),
                savedLoan.getStatus());

        return new LoanResponse(
                savedLoan.getId(),
                savedLoan.getMemberId(),
                savedLoan.getRegisteredBy(),
                savedLoan.getBookIds(),
                savedLoan.getStatus(),
                savedLoan.getCreatedAt(),
                savedLoan.getDueAt(),
                savedLoan.getReturnedAt());
    }
}